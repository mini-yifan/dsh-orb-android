package io.github.ccch1mneyyy.dsh_app.phone

/** Only ids actually returned to this task lifetime may be used by its model. No image is retained. */
class PhoneTaskObservations(private val tasks: PhoneTasks) {
    private data class Seen(val driver: PhoneDriver, val id: String, val owner: PhoneTasks.Owner)
    private val seen = mutableMapOf<PhoneScreen, Seen>()

    fun bind(driver: PhoneDriver, owner: PhoneTasks.Owner): PhoneDriver = object : PhoneDriver by driver {
        private fun current() {
            if (!tasks.isCurrent(owner) || Thread.currentThread().isInterrupted) throw InterruptedException()
        }
        private fun taskAllowed(): Boolean {
            var accepted = false
            tasks.runIfCurrent(owner) { accepted = !tasks.isPaused }
            return accepted
        }
        private fun owns(id: String): Boolean {
            var matched = false
            tasks.runIfCurrent(owner) {
                synchronized(seen) {
                    val value = seen[driver.screen]
                    matched = value != null && value.driver === driver && value.id == id &&
                        value.owner.taskId == owner.taskId && value.owner.lifecycle == owner.lifecycle
                }
            }
            return matched
        }
        private fun remember(snapshot: ScreenSnapshot): ScreenSnapshot {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            // Same order as reads: Tasks -> observation ledger, with owner check and write together.
            if (!tasks.runIfCurrent(owner) {
                synchronized(seen) {
                    seen[driver.screen] = Seen(driver, snapshot.id, owner)
                }
            }) throw InterruptedException()
            return snapshot
        }
        private fun result(result: ActionResult): ActionResult {
            current()
            result.screen?.let(::remember)
            return result
        }
        override fun observe(detail: Boolean, image: Boolean): ScreenSnapshot {
            current()
            return remember(driver.observe(detail, image))
        }
        override fun observe(detail: Boolean, image: Boolean, automaticImage: Boolean): ScreenSnapshot {
            current()
            return remember(driver.observe(detail, image, automaticImage))
        }
        /** A clean screenshot publishes no id, so it registers no observation. */
        override fun captureImage(): ScreenSnapshot {
            current()
            return driver.captureImage()
        }
        override fun acceptVisualObservation(screenId: String) {
            current()
            if (!tasks.runIfCurrent(owner) {
                synchronized(seen) {
                    val value = seen[driver.screen]
                    if (value != null && value.driver === driver && value.id == screenId &&
                        value.owner.taskId == owner.taskId && value.owner.lifecycle == owner.lifecycle) {
                        driver.acceptVisualObservation(screenId) // Memory only; Tasks -> ledger -> driver state.
                    }
                }
            }) throw InterruptedException()
        }
        override fun visualScope(screenId: String): VisualScope? {
            current()
            return if (owns(screenId)) driver.visualScope(screenId) else null
        }
        override fun coordinateTarget(action: PhoneAction.TapXY): VisualTarget? {
            current()
            if (!owns(action.screenId)) return null
            val target = driver.coordinateTarget(action)
            current()
            return target?.takeIf { owns(action.screenId) && driver.visualScope(action.screenId) == it.scope }
        }
        override fun target(screenId: String, elementId: Int): TargetInfo? {
            current()
            return if (owns(screenId)) driver.target(screenId, elementId) else null
        }
        override fun prepareFocusInput(screenId: String): Either<String, FocusInput> {
            current()
            if (!owns(screenId)) return Either.Left("这个屏幕编号不属于本任务的当前观察，请重新 screen。") // l10n:keep agent focus result
            // Capturing nodes or an input connection must never run under Tasks or ledger locks.
            val prepared = driver.prepareFocusInput(screenId)
            if (prepared is Either.Left) { current(); return prepared }
            val original = (prepared as Either.Right).value
            var handedOff = false
            try {
                current()
                if (!owns(screenId))
                    return Either.Left("准备输入期间观察已经改变，没有输入；请重新 screen。") // l10n:keep agent focus result
                val bound = object : FocusInput by original {
                    override fun perform(text: String, allowed: () -> Boolean): ActionResult {
                        current()
                        if (!owns(screenId)) return ActionResult(false, "原焦点观察已经改变，没有输入；请重新 screen。") // l10n:keep agent focus result
                        return result(original.perform(text) { taskAllowed() && owns(screenId) && allowed() })
                    }
                }
                handedOff = true
                return Either.Right(bound)
            } finally {
                if (!handedOff) original.close()
            }
        }
        override fun prepareSkillReplayStep(step: SkillReplayStep): Either<String, SkillReplayPlan> {
            current()
            if (!taskAllowed()) return Either.Left("当前任务已暂停，未准备路线动作。") // l10n:keep
            val prepared = driver.prepareSkillReplayStep(step)
            if (prepared is Either.Left) { current(); return prepared }
            val original = (prepared as Either.Right).value
            var handedOff = false
            try {
                current()
                if (!taskAllowed()) return Either.Left("当前任务已暂停，未准备路线动作。") // l10n:keep
                val bound = object : SkillReplayPlan by original {
                    override val dispatchUncertain: Boolean get() = original.dispatchUncertain
                    override fun perform(allowed: () -> Boolean): ActionResult {
                        current()
                        val value = original.perform { taskAllowed() && allowed() }
                        current()
                        return value // Private route evidence is not a model-returned observation/ledger grant.
                    }
                }
                handedOff = true
                return Either.Right(bound)
            } finally { if (!handedOff) original.close() }
        }
        override fun perform(action: PhoneAction): ActionResult = perform(action) { true }
        override fun perform(action: PhoneAction, allowed: () -> Boolean): ActionResult =
            perform(action, allowed, null) { true }
        override fun perform(action: PhoneAction, allowed: () -> Boolean,
                             coordinateAdmission: ((() -> Boolean) -> Boolean)?): ActionResult =
            perform(action, allowed, coordinateAdmission) { true }
        override fun perform(action: PhoneAction, allowed: () -> Boolean,
                             coordinateAdmission: ((() -> Boolean) -> Boolean)?,
                             actionSourcesAllowed: (Set<String>) -> Boolean): ActionResult {
            current()
            if (action !is PhoneAction.Key && !owns(action.screenId)) {
                // l10n:keep agent observation ownership result
                return ActionResult(false, "这个屏幕编号不属于本任务的当前观察。请本任务重新 screen，不要使用另一对话的编号。")
            }
            return result(driver.perform(action, { taskAllowed() && allowed() }, { claim ->
                var committed = false
                tasks.runIfCurrent(owner) {
                    if (!tasks.isPaused && allowed())
                        committed = coordinateAdmission?.invoke(claim) ?: claim()
                }
                committed
            }, { sources -> taskAllowed() && actionSourcesAllowed(sources) }))
        }
        override fun collect(screenId: String, listElementId: Int, maxPages: Int, until: String?): CollectResult =
            collect(screenId, listElementId, maxPages, until) { true }
        override fun collect(screenId: String, listElementId: Int, maxPages: Int, until: String?, allowed: () -> Boolean): CollectResult =
            collect(screenId, listElementId, maxPages, until, allowed) { true }
        override fun collect(screenId: String, listElementId: Int, maxPages: Int, until: String?,
                             allowed: () -> Boolean, canRead: (String?) -> Boolean): CollectResult =
            collect(screenId, listElementId, maxPages, until, allowed, canRead) { true }
        override fun collect(screenId: String, listElementId: Int, maxPages: Int, until: String?,
                             allowed: () -> Boolean, canRead: (String?) -> Boolean,
                             scrollSourcesAllowed: (Set<String>) -> Boolean): CollectResult {
            current()
            if (!owns(screenId)) return CollectResult(emptyList(), 0, "屏幕编号不属于本任务，请重新 screen", null, rejection = CollectRejection.INVALID_TARGET) // l10n:keep agent observation ownership result
            val result = driver.collect(screenId, listElementId, maxPages, until,
                { taskAllowed() && allowed() }, { pkg -> taskAllowed() && canRead(pkg) },
                { sources -> taskAllowed() && scrollSourcesAllowed(sources) })
            current()
            result.screen?.let(::remember)
            return result
        }
        override fun open(target: PhoneAppIdentity): ActionResult = open(target) { true }
        override fun open(target: PhoneAppIdentity, allowed: () -> Boolean): ActionResult {
            current()
            return result(driver.open(target) { taskAllowed() && allowed() })
        }
        override fun foregroundPackage(): String? {
            current()
            return driver.foregroundPackage()
        }
    }
}
