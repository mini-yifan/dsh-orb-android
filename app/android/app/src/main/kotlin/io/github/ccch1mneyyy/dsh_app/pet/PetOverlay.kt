package io.github.ccch1mneyyy.dsh_app.pet

import io.github.ccch1mneyyy.dsh_app.i18n.AppStrings
import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Choreographer
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import io.github.ccch1mneyyy.dsh_app.MainActivity
import io.github.ccch1mneyyy.dsh_app.R
import io.github.ccch1mneyyy.dsh_app.nativemedia.MicArbiter
import io.github.ccch1mneyyy.dsh_app.phone.PhoneControl
import io.github.ccch1mneyyy.dsh_app.phone.a11y.PhoneA11yService
import io.github.ccch1mneyyy.dsh_app.runtime.DshService
import io.github.ccch1mneyyy.dsh_app.runtime.RuntimeController
import io.github.ccch1mneyyy.dsh_app.shortcuts.EntryActivity
import io.github.ccch1mneyyy.dsh_app.voice.WavRecorder
import java.util.Calendar
import java.util.IdentityHashMap
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The out-of-app pet (docs/design/pets.md): a window the size of the pack's wide hit
 * box (grown around the lifted pose while held, and around an animation that reaches past it while
 * that shows, [PetFrames]), a separate bubble window (with a
 * request's answers on it), a long-press menu, 问一句's input bar, and a 「×」 target while
 * dragging. Dart pushes the reduced state ([push]) and does what the bar and the bubble ask
 * ([Listener.call]); when it shows is [OverlayMachine]'s call, the permission branches
 * [OverlayAccess]'s, the dock [EdgeSnap]'s, what plays [PetPlayer]'s.
 *
 * Window type: TYPE_ACCESSIBILITY_OVERLAY through the phone-control accessibility service when it
 * is on (no other permission), else TYPE_APPLICATION_OVERLAY with 「显示在其他应用上层」. Whether
 * that works is decided by adding the window, not by `canDrawOverlays` (vendor switches).
 *
 * Process-wide, main thread only. [init] comes from [PetOverlayPlugin] with the engine.
 */
internal object PetOverlay {
    interface Listener {
        fun onStatus(status: Map<String, Any?>)
        fun onOpenSettings()

        /** Asks Dart [method] (问一句, an answer, 停止, …); [reply] gets null when Dart can't answer. */
        fun call(method: String, args: Any?, reply: (Any?) -> Unit)
    }

    private const val TAG = "DshPet"
    private const val PREFS = "dsh_pet_overlay"
    private const val K_ENABLED = "enabled"
    private const val K_ALWAYS = "alwaysShow"
    private const val K_SNOOZE = "snoozedUntil"
    private const val K_SIDE = "dockSide"
    private const val K_Y = "dockY"
    private const val EDGE_DP = 8f
    private const val POLL_MS = 2_000L
    private const val OPEN_CHECK_MS = 1_500L
    private const val MENU_MS = 8_000L
    private const val TARGET_DP = 56f
    private const val TARGET_GAP_DP = 28f
    private const val CHANNEL_OPEN = "dsh-pet-open"
    private const val NOTICE_ID = 3091
    /** An answer key takes touches this long after its bubble shows (as phone control's prompt). */
    private const val ARM_MS = 600L
    /** 问一句's bar: as wide as there is room beside the pet, within these. */
    private const val ASK_MIN_DP = 240f
    private const val ASK_MAX_DP = 360f
    /** Let the keyboard and the bar finish hiding before the accessibility screenshot. */
    private const val ASK_SHOT_DELAY_MS = 180L
    /** Less than this much recording is nothing heard (the composer's rule, 0.3 s at 16 kHz). */
    private const val MIN_WAV_BYTES = 44 + 9_600
    /** A recording held this long without the system silencing it is a real one. */
    private const val SILENCE_CHECK_MS = 400L
    /** Orb hold-to-talk: held this long before it counts, slid up this far to cancel, ignored below this. */
    private const val VOICE_LONG_PRESS_MS = 350L
    private const val VOICE_CANCEL_DP = 80f
    private const val VOICE_MIN_MS = 300L
    /** How long the "sent" strip stays before it retracts. */
    private const val SENT_STRIP_MS = 3_000L
    /** The E1/E5 probe: how long each seam variant stays in the cycle. */
    private const val PROBE_STEP_MS = 2_000L
    /** A BadToken from the accessibility overlay keeps the pet on the app overlay this long. */
    private const val A11Y_BAD_TOKEN_MS = 30 * 60_000L
    /** The host field for `EntryActivity.openChat` on this phone. */
    private const val LOCAL_HOST = "{\"kind\":\"local\",\"id\":\"local\"}"

    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var sprites: PetSprites
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "dsh-pet").apply { isDaemon = true } }
    private var initialized = false

    val machine = OverlayMachine()
    private var listener: Listener? = null
    private val producer = OverlayProducerOwner()

    /**
     * The pet is on screen, lit, not hidden by the system, with bubbles on: 「做完了」「出错了」
     * notifications go silent meanwhile (notify/ChatNotifier.kt), the bubble says it.
     */
    @Volatile var speaksForResults = false
        private set

    // ---- permission bookkeeping ----
    private var lastAdd: AddResult? = null
    private var failedAt = 0L
    private var lastCanDraw: Boolean? = null
    private val accessibilityRetry = AccessibilityOverlayRetry()
    private var a11yBadTokenAt = 0L

    // ---- pack ----
    private var loaded: PetSprites.Loaded? = null
    private var loadedKey: String? = null
    private var loadingKey: String? = null
    private var loadRequest = 0L
    private val failedIds = HashSet<String>()

    // ---- windows ----
    private val windows = OverlayWindowOwner()
    private val windowTickets = IdentityHashMap<View, OverlayWindowOwner.Ticket>()
    private var petTicket: OverlayWindowOwner.Ticket? = null
    private var host = OverlayHost.NONE
    private var hostContext: Context? = null
    private var appWindowContext: Context? = null
    private var wm: WindowManager? = null
    private var petView: PetView? = null
    private var petParams: WindowManager.LayoutParams? = null
    private var systemShown = true
    private var bubbleView: BubbleView? = null
    private var bubbleId: String? = null
    private var menuView: PetMenuView? = null
    private var closingMenu: PetMenuView? = null
    private val capture = PetCaptureState()
    private var target: DismissTargetView? = null
    private var targetCenter: WindowPos? = null
    private var dock = Dock.DEFAULT
    private var dimmed = false
    private var dragStart: WindowPos? = null
    /** Where the finger puts the rest frame, kept on screen as before the window grew: release and 「×」 read it. */
    private var dropAt: WindowPos? = null
    private var dragging = false
    private var slideAnim: ValueAnimator? = null
    private var lastVisibility = Visibility.HIDDEN
    private var lastWhy = ""

    // ---- animation ----
    private val player = PetPlayer()
    private var stateKey: String? = null
    private var looping = false

    // ---- app state ----
    private var started = 0
    private var pendingSettings = false
    private var lastStatus: Map<String, Any?>? = null
    private var lastConfig: Configuration? = null
    private var openPendingIntent: PendingIntent? = null

    fun init(context: Context, assetPath: (String) -> String) {
        if (initialized) return
        initialized = true
        app = context.applicationContext
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        askDraft = prefs.getString("ask_draft", "").orEmpty()
        sprites = PetSprites(app, assetPath)
        machine.enabled = prefs.getBoolean(K_ENABLED, true)
        machine.alwaysShow = prefs.getBoolean(K_ALWAYS, true)
        machine.snoozedUntil = prefs.getLong(K_SNOOZE, 0L)
        dock = Dock(
            if (prefs.getString(K_SIDE, null) == "left") Side.LEFT else Side.RIGHT,
            prefs.getFloat(K_Y, Dock.DEFAULT.yFraction),
        )
        if (MainActivity.inForeground) started = 1
        (app as? Application)?.registerActivityLifecycleCallbacks(lifecycle)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(screen, filter, Context.RECEIVER_NOT_EXPORTED) else app.registerReceiver(screen, filter)
        lastConfig = Configuration(app.resources.configuration)
        app.registerComponentCallbacks(config)
        RuntimeController.get(app).addSink { event -> if (event["type"] == "status") main.postDelayed(evaluateRunnable, 300) }
        if (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) PetOverlayProbe.register(app)
        machine.onForeground(started > 0, now())
    }

    // ---- from Dart ------------------------------------------------------------------------------

    fun attachProducer(nextListener: Listener): Long {
        val token = producer.attach()
        resetProducer()
        listener = nextListener
        lastStatus = null
        return token
    }

    fun ownsProducer(token: Long) = producer.owns(token)

    fun detachProducer(token: Long) {
        if (!producer.detach(token)) return
        listener = null
        resetProducer()
    }

    private fun resetProducer() {
        capture.invalidate()
        loadRequest++
        loadingKey = null
        pendingSettings = false
        pendingVoice = false
        returnAsk = false
        voiceHandoff++
        answerState.reset()
        askDraftState.resetProducer()
        machine.resetProducer()
        removeWindows()
        evaluate()
    }

    /** [PetSprites.portrait] of the pack [id]; null before [init] or when it has none. Any thread. */
    fun portrait(id: String, state: String, px: Int): Bitmap? = if (initialized) sprites.portrait(id, state, px) else null

    fun push(args: Map<*, *>) {
        if (!initialized) return
        val p = PetPush.fromMap(args, now())
        machine.onPush(p, now())
        p.packId?.let { ensurePack(it, p.size) }
        retarget()
        evaluate()
    }

    /**
     * A brought pet's files changed (imported again, removed): what was read of [id] is stale even
     * though its id and size are not. Read it again if it is the one out. If it isn't (不显示,
     * another pet), what is kept or still loading of it goes, so choosing it again reads it fresh.
     */
    fun reloadPack(id: String) {
        if (!initialized) return
        failedIds -= id
        val out = machine.last?.takeIf { it.packId == id }
        if (out != null) return ensurePack(id, out.size, reload = true)
        if (loadingKey?.startsWith("$id@") == true) {
            loadRequest++
            loadingKey = null
        }
        if (loadedKey?.startsWith("$id@") != true) return
        loaded?.release()
        loaded = null
        loadedKey = null
        stateKey = null
        if (petView != null) removeWindows()
        evaluate()
    }

    fun setEnabled(on: Boolean): Map<String, Any?> {
        if (!on) capture.invalidate()
        machine.enabled = on
        prefs.edit().putBoolean(K_ENABLED, on).apply()
        if (on) {
            failedIds.clear()
            machine.last?.let { p -> p.packId?.let { ensurePack(it, p.size) } }
        }
        evaluate()
        return status()
    }

    fun setAlwaysShow(on: Boolean): Map<String, Any?> {
        machine.alwaysShow = on
        prefs.edit().putBoolean(K_ALWAYS, on).apply()
        evaluate()
        return status()
    }

    fun unsnooze(): Map<String, Any?> {
        machine.snoozedUntil = 0L
        prefs.edit().putLong(K_SNOOZE, 0L).apply()
        evaluate()
        return status()
    }

    /** Status after a real check: adds (and removes) an invisible window unless the pet's own proves it. */
    fun permissionStatus(): Map<String, Any?> {
        refreshCanDraw()
        if (!a11yConnected() && !(host == OverlayHost.APPLICATION && petView?.isAttachedToWindow == true)) probe()
        evaluate()
        return status()
    }

    fun status(): Map<String, Any?> {
        val canDraw = canDraw()
        val a11y = a11yConnected()
        return mapOf(
            "enabled" to machine.enabled,
            "alwaysShow" to machine.alwaysShow,
            "permission" to OverlayAccess.permission(a11y, canDraw, lastAdd).wire,
            "canDraw" to canDraw,
            "accessibility" to a11y,
            "snoozed" to machine.snoozed(now()),
            "showing" to (petView?.isAttachedToWindow == true && !capture.inFlight),
            "sdk" to Build.VERSION.SDK_INT,
            "manufacturer" to Build.MANUFACTURER,
        )
    }

    /** 「今天不再显示」: hidden until local midnight. */
    fun snoozeToday() {
        capture.invalidate()
        val c = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        machine.snoozedUntil = c.timeInMillis
        prefs.edit().putLong(K_SNOOZE, c.timeInMillis).apply()
        evaluate()
    }

    // ---- the rules, applied ---------------------------------------------------------------------

    private fun now() = System.currentTimeMillis()

    private val evaluateRunnable = Runnable { evaluate() }

    fun evaluate() {
        if (!initialized) return
        main.removeCallbacks(evaluateRunnable)
        if (capture.inFlight) {
            // Keep the invisible PET ticket for openIntent's authenticated generation, but never
            // let polling, an incoming Dart push or window-cleanup callbacks re-show captured UI.
            stopLoop()
            petView?.visibility = View.INVISIBLE
            speaksForResults = false
            publishStatus()
            return
        }
        refreshCanDraw()
        machine.canHost = overlayHost() != OverlayHost.NONE
        machine.hasPet = loaded != null && loadedKey == packKey(machine.last)
        machine.phoneTask = PhoneControl.peek()?.foregroundLine() != null
        machine.dshService = DshService.isRunning
        machine.interactive = interactive()
        machine.holding = askView != null || menuView != null || answerState.bubbleId != null || returnAsk
        val t = now()
        val d = machine.decide(t)
        if (d.visibility != lastVisibility || d.why != lastWhy) {
            Log.i(TAG, "overlay ${d.visibility.name.lowercase()}: ${d.why}")
            lastVisibility = d.visibility
            lastWhy = d.why
        }
        when (d.visibility) {
            Visibility.HIDDEN -> {
                stopLoop()
                removeWindows()
            }
            Visibility.PAUSED -> {
                petView?.cancelInteraction()
                stopLoop()
                dismissMenu()
                removeAsk()
                cancelVoiceAndStrip()
            }
            Visibility.SHOWN -> {
                val before = lastAdd
                if (!attachPet()) {
                    // With the permission now known not to work, decide again; else retry at the next poll.
                    if (lastAdd != before) main.post(evaluateRunnable)
                } else {
                    petView?.visibility = View.VISIBLE
                    retarget()
                    if (!dragging) setDimmed(d.dimmed)
                    if (dimmed || !systemShown) stopLoop() else startLoop()
                    showBubble(d.bubble)
                    if (returnAsk) {
                        returnAsk = false
                        main.post { if (!machine.appForeground && alive(petTicket)) showAsk(askFresh) }
                    }
                }
            }
        }
        speaksForResults = ResultSilence.petSpeaks(d.visibility, petView?.isAttachedToWindow == true, systemShown, machine.last?.quiet == true)
        PetOverlayService.ensure(app, d.ownService)
        // Polled only while out (or held back by a phone task): the accessibility service, a phone
        // task and DshService change without telling us.
        val poll = d.visibility == Visibility.SHOWN || (machine.phoneTask && machine.enabled && !machine.appForeground)
        val wake = listOfNotNull(
            d.wakeAt?.let { it - t }, if (poll) POLL_MS else null,
            accessibilityRetry.remaining(PhoneA11yService.instance, t),
        ).minOrNull()
        if (wake != null) main.postDelayed(evaluateRunnable, wake.coerceAtLeast(50))
        // The status strip follows the pet's visibility (app foreground, snooze, rotation).
        syncOrbStrip()
        syncBookmarks()
        publishStatus()
    }

    private fun publishStatus() {
        val s = status()
        if (s != lastStatus) {
            lastStatus = s
            listener?.onStatus(s)
        }
    }

    // ---- permission -----------------------------------------------------------------------------

    private fun a11yConnected() = PhoneA11yService.instance != null

    private fun overlayHost(): OverlayHost {
        val service = PhoneA11yService.instance
        // A stale accessibility window token (seen right after an APK update, and while
        // two accessibility services flap) otherwise re-fails every 60 s and tears the
        // pet down each time. Stay on the app overlay for a while after one.
        val badToken = a11yBadTokenAt != 0L && now() - a11yBadTokenAt < A11Y_BAD_TOKEN_MS
        return OverlayAccess.host(
            a11yConnected(), lastAdd,
            a11yFailed = badToken || accessibilityRetry.remaining(service, now()) != null,
            canDraw = canDraw(),
        )
    }

    private fun canDraw(): Boolean = try { Settings.canDrawOverlays(app) } catch (_: Exception) { false }

    private fun refreshCanDraw() {
        val cd = canDraw()
        val changed = lastCanDraw != null && lastCanDraw != cd
        lastCanDraw = cd
        if (lastAdd == AddResult.FAILED && OverlayAccess.retry(lastAdd, failedAt, now(), cd, changed)) lastAdd = null
    }

    /** Adds a 1×1 invisible application overlay and removes it again; the result is the permission. */
    private fun probe() {
        val ctx = appWindowContext()
        val w = ctx.getSystemService(WindowManager::class.java) ?: return
        val v = View(ctx)
        val p = WindowManager.LayoutParams(
            1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { alpha = 0f; gravity = Gravity.TOP or Gravity.START; title = AppStrings.get(app, R.string.pet_window_title) }
        lastAdd = try {
            w.addView(v, p)
            try { w.removeViewImmediate(v) } catch (_: Exception) {}
            AddResult.OK
        } catch (e: Exception) {
            Log.i(TAG, "overlay permission probe failed: ${e.javaClass.simpleName}")
            failedAt = now()
            AddResult.FAILED
        }
    }

    // ---- pack -----------------------------------------------------------------------------------

    private fun packKey(p: PetPush?): String? = p?.packId?.let { "$it@${p.size}" }

    /** Loads [id] at [size] unless it is loaded, loading or broken; [reload]: from disk again regardless. */
    private fun ensurePack(id: String, size: String, reload: Boolean = false) {
        val key = "$id@$size"
        if (!reload && (key == loadedKey || key == loadingKey || id in failedIds)) return
        loadingKey = key
        val request = ++loadRequest
        val density = app.resources.displayMetrics.density
        worker.execute {
            val l = sprites.load(id, worker) { pack -> pack.screenScale(PetSize.fromName(size), density) }
            l?.warmResident()
            main.post {
                if (request != loadRequest || loadingKey != key || packKey(machine.last) != key) {
                    if (request == loadRequest) loadingKey = null
                    l?.release()
                    return@post
                }
                loadingKey = null
                if (l == null) {
                    Log.w(TAG, "pet pack $id not found")
                    failedIds += id
                    // Read again (reloadPack) and gone or broken now: the old picture doesn't stay.
                    if (loadedKey == key) {
                        loaded?.release()
                        loaded = null
                        loadedKey = null
                        stateKey = null
                        if (petView != null) removeWindows()
                    }
                } else {
                    loaded?.release()
                    loaded = l
                    loadedKey = key
                    stateKey = null
                    if (petView != null) removeWindows()
                }
                evaluate()
            }
        }
    }

    // ---- animation ------------------------------------------------------------------------------

    /** Starts the pushed state's animation; restarts only when the state, tier or pack changes. */
    private fun retarget() {
        val l = loaded ?: return
        val p = machine.last ?: return
        // The floating pet doesn't roam: roam plays as idle.
        val state = if (p.state == PetPush.ROAM) PetPack.IDLE else p.state
        val key = "${l.pack.id}|$state|${p.tier}|${p.reduceMotion}"
        if (key == stateKey) return
        stateKey = key
        val pack = l.pack
        // Calm states hold a still frame and play one action every 8–15 s: for idle,
        // a cycle of the plain idle (blink, breath) or now and then one from the pack's idle pool.
        val calmPool = when (state) {
            PetPack.IDLE -> pack.primary(state, 0).let { base -> listOf(PetIdleItem(base, base.totalMs, 3)) } + pack.idlePool
            PetPush.SLEEPING -> pack.primary(state, 0).let { listOf(PetIdleItem(it, it.totalMs, 1)) }
            else -> null
        }
        player.setState(pack.primary(state, p.tier), calmPool, pack.bonusOf(state, p.tier), SystemClock.uptimeMillis())
        if (looping) restartLoop() else petView?.let { drawStill() }
    }

    private fun react(kind: String, loop: Boolean) {
        val a = loaded?.pack?.reaction(kind) ?: return
        player.react(a, loop, SystemClock.uptimeMillis())
        restartLoop()
    }

    private var frame: Choreographer.FrameCallback? = null

    private fun startLoop() {
        if (looping) return
        if (!alive(petTicket)) return
        looping = true
        tick()
    }

    private fun restartLoop() {
        if (!looping) return
        frame?.let { Choreographer.getInstance().removeFrameCallback(it) }
        frame = null
        tick()
    }

    private fun stopLoop() {
        looping = false
        frame?.let { Choreographer.getInstance().removeFrameCallback(it) }
        frame = null
    }

    /** One frame, then sleep until the picture next changes (the still idle frame waits seconds). */
    private fun tick() {
        if (!looping) return
        val ticket = petTicket
        if (!alive(ticket)) return
        val view = petView ?: return
        val l = loaded ?: return
        player.still = !animationsOn()
        val f = player.frame(SystemClock.uptimeMillis()) ?: return
        if (!view.show(f.anim, f.index)) {
            l.request(f.anim) { if (alive(ticket) && loaded === l) redraw() }
            return
        }
        onShown()
        f.nextInMs?.let { delay ->
            lateinit var callback: Choreographer.FrameCallback
            callback = Choreographer.FrameCallback {
                if (frame === callback) {
                    frame = null
                    if (alive(ticket)) tick()
                }
            }
            frame = callback
            Choreographer.getInstance().postFrameCallbackDelayed(callback, delay.coerceAtLeast(8))
        }
    }

    /** Paused or dimmed: still draw the current picture once (sheet permitting). */
    private fun drawStill() {
        val ticket = petTicket
        if (!alive(ticket)) return
        val view = petView ?: return
        val l = loaded ?: return
        val f = player.frame(SystemClock.uptimeMillis()) ?: return
        if (view.show(f.anim, f.index)) onShown() else l.request(f.anim) { if (alive(ticket) && loaded === l) redraw() }
    }

    /** A picture is on screen: the window fits it, a bubble moves over it. */
    private fun onShown() {
        fitFrame()
        followBubble()
    }

    /** A sheet arrived: carry on playing, or show its still picture. */
    private fun redraw() {
        if (looping) restartLoop() else drawStill()
    }

    // ---- the window's frame ---------------------------------------------------------------------

    /** Where the rest frame sits now: the window, less how far its frame reaches past rest. */
    private fun restPos(v: PetView, p: WindowManager.LayoutParams) = PetFrames.restOf(WindowPos(p.x, p.y), v.frame)

    private fun placeRest(v: PetView, p: WindowManager.LayoutParams, rest: WindowPos) {
        val at = PetFrames.windowAt(rest, v.frame)
        p.x = at.x
        p.y = at.y
    }

    /**
     * Sizes the pet window to what it shows (docs/design/pets.md §6.1): grown around the lifted pose
     * while held and until that picture is gone, around the shown animation's whole cell when it
     * reaches past rest (a jump, typing's laptop, a thought cloud, Zzz), the rest frame otherwise,
     * so every frame is drawn whole. The rest frame keeps its place, so the pet doesn't move on
     * screen: held, the grown window is kept whole on screen; docked, the dock already left room for
     * every frame ([PetFrames.reach]). One window update when the frame changes, none while it stays.
     */
    private fun fitFrame() {
        val v = petView ?: return
        val p = petParams ?: return
        val l = loaded ?: return
        if (!alive(petTicket)) return
        val next = l.frames.frameFor(v.shown, dragging)
        if (next == v.frame) return
        val rest = restPos(v, p)
        v.setFrame(next)
        p.width = next.width
        p.height = next.height
        placeRest(v, p, rest)
        if (dragging) EdgeSnap.clampDrag(p.x, p.y, p.width, p.height, safeArea()).let { p.x = it.x; p.y = it.y }
        update(v, p)
    }

    // ---- windows --------------------------------------------------------------------------------

    private fun appWindowContext(): Context {
        appWindowContext?.let { return it }
        val ctx = if (Build.VERSION.SDK_INT >= 30) {
            val display = app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            app.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else {
            app
        }
        appWindowContext = ctx
        return ctx
    }

    private fun windowType(h: OverlayHost) =
        if (h == OverlayHost.ACCESSIBILITY) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun baseParams(w: Int, h: Int, flags: Int) = WindowManager.LayoutParams(
        w, h, windowType(host),
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or flags,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        title = AppStrings.get(app, R.string.pet_window_title)
        if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
        // A window whose size and place change in one layout gets the system's ~0.4 s move
        // animation, and every move meanwhile is ignored: picked up, the pet grows around the
        // lifted pose and then lagged the finger until it ended. Our windows move themselves.
        if (Build.VERSION.SDK_INT >= 34) {
            setCanPlayMoveAnimation(false)
        } else {
            // PRIVATE_FLAG_NO_MOVE_ANIMATION, what setCanPlayMoveAnimation sets.
            runCatching { javaClass.getField("privateFlags").run { setInt(this@apply, getInt(this@apply) or 0x40) } }
        }
    }

    private fun edge(ctx: Context) = ctx.dp(EDGE_DP)

    /** The pet window, on the right host; false when it couldn't be added. */
    private fun attachPet(): Boolean {
        val want = overlayHost()
        if (want == OverlayHost.NONE) return false
        val service = PhoneA11yService.instance
        return try {
            val ctx = if (want == OverlayHost.ACCESSIBILITY) service?.overlayWindowContext() ?: return false else appWindowContext()
            val current = petView
            if (current != null && host == want && windows.isHost(ctx) && alive(petTicket)) return true
            removeWindows()
            if (!windows.begin(ctx)) return false
            val w = ctx.getSystemService(WindowManager::class.java) ?: return false
            host = want
            hostContext = ctx
            wm = w
            val ticket = windows.claim(OverlayWindowOwner.Role.PET) ?: return false
            petTicket = ticket
            val view = PetView(ctx, petListener(ticket)).apply {
                setSprites(loaded)
                longPressMs = VOICE_LONG_PRESS_MS
            }
            if (view.pixelWidth <= 0) {
                removeWindows()
                return false
            }
            view.contentDescription = loaded?.pack?.name ?: AppStrings.get(app, R.string.pet_label)
            dimmed = false
            systemShown = true
            val pos = EdgeSnap.position(dock, view.reach, safeArea(), edge(ctx))
            val params = baseParams(view.pixelWidth, view.pixelHeight, 0).apply { x = pos.x; y = pos.y }
            petView = view
            petParams = params
            // The 「×」 goes in first, hidden, so the dragged pet stays above it.
            addTarget(ctx, w)
            if (!installWindow(ticket, view, w, params)) {
                removeWindows()
                return false
            }
            if (want == OverlayHost.APPLICATION) lastAdd = AddResult.OK
            enter(view)
            drawStill()
            true
        } catch (e: Exception) {
            Log.i(TAG, "overlay add failed (${want.name.lowercase()}): ${e.javaClass.simpleName}")
            removeWindows()
            if (want == OverlayHost.APPLICATION) {
                lastAdd = AddResult.FAILED
                failedAt = now()
            } else if (service != null) {
                accessibilityRetry.failed(service, now())
                a11yBadTokenAt = now()
                service.invalidateOverlayWindowContext()
                // With the failure cooldown in place the next evaluate picks the
                // application overlay (when allowed) instead of leaving no pet
                // for a whole minute. A stale accessibility window token after
                // an app update is exactly this case.
                main.post(evaluateRunnable)
            }
            host = OverlayHost.NONE
            false
        }
    }

    private fun removeWindows() {
        // Invalidate input/async callbacks before cancel() or platform removal can call us back.
        discardOrbLayers()
        windows.retire()
        stopLoop()
        openPendingIntent?.cancel()
        openPendingIntent = null
        if (initialized) app.getSystemService(NotificationManager::class.java)?.cancel(NOTICE_ID)
        slideAnim?.cancel()
        slideAnim = null
        dismissMenu()
        removeAsk()
        removeBubble()
        removeTarget()
        petView?.let { remove(it) }
        petView = null
        petTicket = null
        petParams = null
        dragging = false
        dimmed = false
        player.endReaction()
        speaksForResults = false
        host = OverlayHost.NONE
        hostContext = null
        wm = null
        scheduleCleanup()
    }

    private fun update(v: View, p: WindowManager.LayoutParams) {
        if (!alive(windowTickets[v])) return
        try { wm?.updateViewLayout(v, p) } catch (e: Exception) { Log.w(TAG, "overlay update failed", e) }
    }

    private fun remove(v: View) {
        val ticket = windowTickets.remove(v)
        v.visibility = View.GONE // A deferred or failed platform removal must not leak UI into a screenshot.
        windows.remove(ticket)
        v.animate().cancel()
        scheduleCleanup()
    }

    private fun alive(ticket: OverlayWindowOwner.Ticket?) = windows.owns(petTicket) && windows.owns(ticket)

    private val cleanupRunnable = Runnable {
        windows.retryRemovals()
        scheduleCleanup()
        evaluate()
    }

    private fun scheduleCleanup() {
        main.removeCallbacks(cleanupRunnable)
        if (windows.pendingRemovals > 0) main.postDelayed(cleanupRunnable, POLL_MS)
    }

    private fun installWindow(ticket: OverlayWindowOwner.Ticket, v: View, manager: WindowManager, p: WindowManager.LayoutParams): Boolean {
        val attached = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) {
                if (!windows.owns(ticket)) return
                windows.revoke(ticket)
                main.post {
                    if (petTicket == ticket) removeWindows() else if (windowTickets[v] == ticket) remove(v)
                    evaluate()
                }
            }
        }
        v.addOnAttachStateChangeListener(attached)
        windowTickets[v] = ticket
        val removal = OverlayRemoval(
            request = {
                // The exact manager which accepted add; pending attachment is still owned.
                try {
                    manager.removeViewImmediate(v)
                } catch (e: IllegalArgumentException) {
                    // WindowManager reports an unregistered view, including failed add.
                    // Do not infer this from attachment state without actually trying remove.
                    if (v.isAttachedToWindow || v.parent != null) throw e
                }
            },
            detached = { !v.isAttachedToWindow && v.parent == null },
            nextTurn = { confirm ->
                main.post {
                    confirm()
                    // A deferred removal still attached after this turn must wait for the poll;
                    // retrying it here can post another confirmation forever on the main thread.
                    if (!v.isAttachedToWindow && v.parent == null) {
                        windows.retryRemovals()
                        evaluate()
                    }
                    scheduleCleanup()
                }
            },
        )
        return try {
            windows.install(ticket, remove = {
                if (removal.attempt()) {
                    v.removeOnAttachStateChangeListener(attached)
                    if (windowTickets[v] == ticket) windowTickets.remove(v)
                    true
                } else {
                    false
                }
            }, add = { manager.addView(v, p) })
        } finally {
            scheduleCleanup()
        }
    }

    private val easing = PathInterpolator(0.2f, 0f, 0f, 1f)

    private fun animationsOn() = machine.last?.reduceMotion != true && ValueAnimator.areAnimatorsEnabled()

    private fun enter(v: PetView) {
        if (!animationsOn()) return
        v.alpha = 0f
        v.scaleX = 0.9f
        v.scaleY = 0.9f
        v.pivotX = v.pixelWidth / 2f
        v.pivotY = v.pixelHeight.toFloat()
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(easing).start()
    }

    /** Fades to [OverlayMachine.DIM_ALPHA] and back where it docks; the whole pet stays on screen. */
    private fun setDimmed(on: Boolean) {
        if (on == dimmed) return
        val v = petView ?: return
        dimmed = on
        if (on) {
            dismissMenu()
            removeBubble()
        }
        val alpha = if (on) OverlayMachine.DIM_ALPHA else 1f
        if (animationsOn()) v.animate().alpha(alpha).setDuration(if (on) 400 else 150).setInterpolator(easing).start() else v.alpha = alpha
    }

    /** The screen minus status / navigation bars and the cutout. */
    private fun safeArea(): SafeArea {
        val w = wm ?: appWindowContext().getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= 30) {
            val m = w.currentWindowMetrics
            val i = m.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            return SafeArea(m.bounds.width(), m.bounds.height(), i.left, i.top, i.right, i.bottom)
        }
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        val display = w.defaultDisplay
        @Suppress("DEPRECATION")
        display.getRealMetrics(dm)
        val res = Resources.getSystem()
        fun dimen(name: String) = res.getIdentifier(name, "dimen", "android").takeIf { it != 0 }?.let(res::getDimensionPixelSize) ?: 0
        val status = dimen("status_bar_height")
        val nav = dimen("navigation_bar_height")
        return when (display.rotation) {
            Surface.ROTATION_90 -> SafeArea(dm.widthPixels, dm.heightPixels, 0, status, nav, 0)
            Surface.ROTATION_270 -> SafeArea(dm.widthPixels, dm.heightPixels, nav, status, 0, 0)
            else -> SafeArea(dm.widthPixels, dm.heightPixels, 0, status, 0, nav)
        }
    }

    // ---- touch ----------------------------------------------------------------------------------

    private fun petListener(ticket: OverlayWindowOwner.Ticket) = object : PetView.Listener {
        override fun onDragStart() {
            if (!alive(ticket)) return
            val v = petView ?: return
            val p = petParams ?: return
            slideAnim?.cancel()
            // Kept as the rest frame's place: the window grows around the lifted pose (fitFrame).
            dragStart = restPos(v, p)
            dropAt = dragStart
            dragging = true
            setDimmed(false)
            machine.onTouch(now())
            cancelVoiceAndStrip()
            dismissMenu()
            removeBubble()
            showTarget()
            react("drag", loop = true)
            startLoop()
            fitFrame()
        }

        override fun onDrag(dx: Int, dy: Int) {
            if (!alive(ticket)) return
            val v = petView ?: return
            val p = petParams ?: return
            val s = dragStart ?: return
            val area = safeArea()
            // The window, lifted pose and all, stays whole on screen.
            val pos = EdgeSnap.carry(s, dx, dy, v.frame, area)
            p.x = pos.x
            p.y = pos.y
            update(v, p)
            // Release and 「×」 read the rest frame where the finger puts it, kept on screen as it
            // always was: docking doesn't change, though at the top the held window stops earlier.
            val drop = EdgeSnap.clampDrag(s.x + dx, s.y + dy, v.pixelWidth, v.pixelHeight, area)
            dropAt = drop
            val c = targetCenter
            target?.setArmed(c != null && EdgeSnap.overDismiss(drop, v.pixelWidth, v.pixelHeight, c, v.context.dp(TARGET_DP)))
        }

        override fun onDragEnd(velocityX: Float) {
            if (!alive(ticket)) return
            val v = petView ?: return
            val p = petParams ?: return
            dragging = false
            player.endReaction()
            val dismiss = target?.armed == true
            hideTarget()
            machine.onTouch(now())
            if (dismiss) {
                // Dropped on 「×」: today it stays away.
                snoozeToday()
                return
            }
            // Put down: the state's picture now, and the window back to rest around the same
            // origin (later, if that picture's sheet has to be decoded again first).
            redraw()
            fitFrame()
            val area = safeArea()
            val drop = dropAt ?: restPos(v, p)
            dock = EdgeSnap.dockFor(drop, v.pixelWidth, v.reach, area, edge(v.context), velocityX)
            prefs.edit().putString(K_SIDE, if (dock.side == Side.LEFT) "left" else "right").putFloat(K_Y, dock.yFraction).apply()
            slide(v, p, EdgeSnap.position(dock, v.reach, area, edge(v.context))) { evaluate() }
        }

        override fun onDragCancel() {
            if (!alive(ticket)) return
            dragging = false
            player.endReaction()
            hideTarget()
            redraw()
            val v = petView ?: return
            val p = petParams ?: return
            slide(v, p, EdgeSnap.position(dock, v.reach, safeArea(), edge(v.context))) { evaluate() }
        }

        override fun onTap(leftHalf: Boolean) {
            if (!alive(ticket)) return
            machine.onTouch(now())
            if (dimmed) evaluate()
            dismissMenu()
            react(if (leftHalf) "tapLeft" else "tapRight", loop = false)
            val run = orbRun
            val busy = run?.running == true || run?.state == "waiting-approval" || run?.state == "waiting-question"
            if (busy) {
                // A run owns the strip; typing into it is the app's composer. The strip's ■
                // stays one tap away outside.
                open(run?.sessionId, run?.host, null)
            } else {
                // The ball's primary gesture: type into the local Orb conversation.
                showAsk(fresh = false, orb = true)
            }
        }

        override fun onLongPress(rawX: Float?, rawY: Float?) {
            if (!alive(ticket)) return
            machine.onTouch(now())
            if (dimmed) evaluate()
            // TalkBack's long-click has no pointer: the old bar is the accessible text path.
            if (rawX == null || rawY == null) {
                showAsk(fresh = false)
                return
            }
            startVoice(rawX, rawY)
        }

        override fun onMenuMove(rawX: Float, rawY: Float) {
            if (alive(ticket)) moveVoice(rawY)
        }

        override fun onMenuEnd(rawX: Float, rawY: Float, cancelled: Boolean) {
            if (!alive(ticket)) return
            endVoice(cancelled)
        }

        override fun onShownChanged(shown: Boolean) {
            if (!alive(ticket)) return
            if (shown == systemShown) return
            systemShown = shown
            if (!shown) {
                dismissMenu()
                cancelVoiceAndStrip()
            }
            // Hidden by the system (another app's setHideOverlayWindows): wait, don't re-add.
            main.post(evaluateRunnable)
        }
    }

    /** Slides the rest frame to [to]; the window follows with whatever frame it has at each step. */
    private fun slide(v: PetView, p: WindowManager.LayoutParams, to: WindowPos, then: (() -> Unit)?) {
        val ticket = windowTickets[v]
        if (!alive(ticket)) return
        slideAnim?.cancel()
        if (!animationsOn()) {
            placeRest(v, p, to)
            update(v, p)
            then?.invoke()
            return
        }
        val from = restPos(v, p)
        slideAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            interpolator = easing
            addUpdateListener { a ->
                if (!alive(ticket)) return@addUpdateListener
                val f = a.animatedValue as Float
                placeRest(v, p, WindowPos((from.x + (to.x - from.x) * f).roundToInt(), (from.y + (to.y - from.y) * f).roundToInt()))
                update(v, p)
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (slideAnim === animation) slideAnim = null
                    if (!cancelled && alive(ticket)) then?.invoke()
                }
            })
            start()
        }
    }

    // ---- 「×」 target ----------------------------------------------------------------------------

    private fun addTarget(ctx: Context, w: WindowManager) {
        removeTarget()
        val ticket = windows.claim(OverlayWindowOwner.Role.TARGET) ?: return
        val size = (ctx.dp(TARGET_DP) * 1.2f).roundToInt()
        val c = EdgeSnap.dismissCenter(safeArea(), size, ctx.dp(TARGET_GAP_DP))
        val view = DismissTargetView(ctx).apply { visibility = View.GONE }
        val params = baseParams(size, size, WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE).apply {
            x = c.x - size / 2
            y = c.y - size / 2
        }
        target = view
        targetCenter = c
        installWindow(ticket, view, w, params)
    }

    private fun showTarget() {
        val view = target ?: return
        view.setArmed(false)
        view.visibility = View.VISIBLE
        if (animationsOn()) {
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(150).start()
        }
    }

    private fun hideTarget() {
        target?.let {
            it.animate().cancel()
            it.visibility = View.GONE
        }
    }

    private fun removeTarget() {
        target?.let { remove(it) }
        target = null
        targetCenter = null
    }

    // ---- bubble ---------------------------------------------------------------------------------

    private fun showBubble(b: PetBubble?) {
        if (b == null || dragging || dimmed || slideAnim != null || menuView != null || closingMenu != null || capture.inFlight) {
            removeBubble()
            return
        }
        val ctx = hostContext ?: return
        if (bubbleId == b.id && alive(bubbleView?.let { windowTickets[it] })) return
        removeBubble()
        val ticket = windows.claim(OverlayWindowOwner.Role.BUBBLE) ?: return
        val view = BubbleView(ctx) { if (alive(ticket)) onBubbleTap(b) }
        view.bind(b)
        b.ask?.let { ask ->
            // Laid out once first: the buttons depend on whether the request's text shows whole.
            view.measureCard()
            view.setActions(BubbleAction.of(ask, view.detailWhole()), ask.kind, SystemClock.uptimeMillis() + ARM_MS) { a ->
                if (alive(ticket)) onBubbleAction(b, a)
            }
        }
        val card = view.measureCard()
        val shown = petView?.shown?.name
        val place = bubblePlace(card, view.tailPx) ?: return
        view.setTail(place.tailAt, place.tail)
        val params = baseParams(place.w, place.h, 0).apply { x = place.pos.x; y = place.pos.y }
        try {
            val manager = wm ?: return
            if (!installWindow(ticket, view, manager, params)) return
        } catch (e: Exception) {
            Log.w(TAG, "bubble add failed", e)
            return
        }
        bubbleView = view
        bubbleId = b.id
        bubbleParams = params
        bubbleCard = card
        bubbleFor = shown
        if (animationsOn()) {
            // Comes out of the tail's side, towards where it rests.
            val d = ctx.dp(4f).toFloat()
            view.alpha = 0f
            when (place.tail) {
                Tail.DOWN -> view.translationY = d
                Tail.UP -> view.translationY = -d
                Tail.RIGHT -> view.translationX = d
                Tail.LEFT -> view.translationX = -d
            }
            view.animate().alpha(1f).translationX(0f).translationY(0f).setDuration(200).setInterpolator(easing).start()
        }
    }

    /**
     * Where the bubble goes for the picture on screen now (docs/design/pets.md §2.7): over the
     * body and the current animation's cell, so a jump or a thought cloud stays in front of it.
     */
    private fun bubblePlace(card: Pair<Int, Int>, tailPx: Int): BubblePlacement? {
        val ctx = hostContext ?: return null
        val pv = petView ?: return null
        val pp = petParams ?: return null
        val f = loaded?.frames ?: return null
        val rest = restPos(pv, pp)
        return EdgeSnap.bubble(
            f.body.at(rest), f.art(pv.shown).at(rest), dock.side, card.first, card.second, tailPx, safeArea(),
            gap = ctx.dp(4f), margin = edge(ctx), tailInset = ctx.dp(20f),
        )
    }

    private var bubbleParams: WindowManager.LayoutParams? = null
    private var bubbleCard: Pair<Int, Int>? = null
    /** The animation the bubble was placed over. */
    private var bubbleFor: String? = null
    private var bubbleMove: ValueAnimator? = null

    /**
     * The picture under a bubble changed (idle to a jump, say): the bubble glides to its place over
     * the new one, the way the chat page's bubble follows the head. Nothing to do while it stays.
     */
    private fun followBubble() {
        val view = bubbleView ?: return
        val p = bubbleParams ?: return
        val card = bubbleCard ?: return
        val ticket = windowTickets[view]
        if (!alive(ticket)) return
        val shown = petView?.shown?.name
        if (shown == bubbleFor) return
        bubbleFor = shown
        val place = bubblePlace(card, view.tailPx) ?: return
        bubbleMove?.cancel()
        view.setTail(place.tailAt, place.tail)
        p.width = place.w
        p.height = place.h
        val from = WindowPos(p.x, p.y)
        if (!animationsOn() || from == place.pos) {
            p.x = place.pos.x
            p.y = place.pos.y
            update(view, p)
            return
        }
        bubbleMove = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200
            interpolator = easing
            addUpdateListener { a ->
                if (!alive(ticket) || bubbleParams !== p) return@addUpdateListener
                val t = a.animatedValue as Float
                p.x = (from.x + (place.pos.x - from.x) * t).roundToInt()
                p.y = (from.y + (place.pos.y - from.y) * t).roundToInt()
                update(view, p)
            }
            start()
        }
    }

    private fun removeBubble() {
        bubbleMove?.cancel()
        bubbleMove = null
        bubbleView?.let { remove(it) }
        bubbleView = null
        bubbleId = null
        bubbleParams = null
        bubbleCard = null
        bubbleFor = null
    }

    private fun onBubbleTap(b: PetBubble) {
        machine.onTouch(now())
        machine.bubbles.dismiss()
        removeBubble()
        val push = machine.last
        if (b.sessionId != null) open(b.sessionId, b.host, b.title) else open(push?.sessionId, push?.host, b.title)
    }

    /** The bubble whose answer is on its way to Dart. */
    private val answerState = PetAnswerState()

    /**
     * An answer from the bubble, through Dart's own path (the one the notification's buttons
     * take). Answered or settled elsewhere, the bubble goes; refused, it says so and leads to the chat.
     */
    private fun onBubbleAction(b: PetBubble, a: BubbleAction) {
        machine.onTouch(now())
        val ask = b.ask ?: return
        if (a.kind == BubbleAction.Kind.OPEN) return onBubbleTap(b)
        val view = bubbleView ?: return
        val answer = answerState.begin(b.id) ?: return
        view.setBusy(a)
        val args = mapOf(
            "host" to b.host, "sessionId" to b.sessionId, "eventId" to ask.eventId, "token" to ask.token, "kind" to ask.kind,
            "decision" to when (a.kind) {
                BubbleAction.Kind.ALLOW -> "allow"
                BubbleAction.Kind.REJECT -> "reject"
                else -> null
            },
            "choice" to a.choice,
        )
        callDart("answer", args) { raw ->
            if (!answerState.finish(answer)) return@callDart
            val result = (raw as? Map<*, *>)?.get("result")
            Log.i(TAG, "bubble answer ${a.kind.name.lowercase()}: $result")
            if (bubbleId == b.id) {
                if (result == "handled" || result == "gone") {
                    machine.bubbles.dismiss()
                    removeBubble()
                } else {
                    bubbleView?.let { v ->
                        v.showFailed(AppStrings.get(app, R.string.pet_answer_failed), ask.kind) { onBubbleTap(b) }
                        refitBubble(v)
                    }
                }
            }
            evaluate()
        }
    }

    /** The bubble's content changed size: measured and placed again where it is. */
    private fun refitBubble(view: BubbleView) {
        val p = bubbleParams ?: return
        val card = view.measureCard()
        bubbleCard = card
        val place = bubblePlace(card, view.tailPx) ?: return
        view.setTail(place.tailAt, place.tail)
        p.width = place.w
        p.height = place.h
        p.x = place.pos.x
        p.y = place.pos.y
        update(view, p)
    }

    // ---- menu -----------------------------------------------------------------------------------

    private var menuTimeout: Runnable? = null
    private var menuParams: WindowManager.LayoutParams? = null

    private fun showMenu(rawX: Float? = null, rawY: Float? = null) {
        if (capture.inFlight || !interactive()) return
        val currentTicket = menuView?.let { windowTickets[it] }
        if (currentTicket != null && alive(currentTicket)) {
            // Reuse the live menu: removing it would reserve MENU until the
            // next-loop confirmation, so an immediate replacement cannot add.
            if (rawX != null && rawY != null) menuView?.beginPointer(rawX, rawY)
            scheduleMenuTimeout(currentTicket)
            return
        }
        dismissMenu()
        val ctx = hostContext ?: return
        val ticket = windows.claim(OverlayWindowOwner.Role.MENU) ?: return
        slideAnim?.cancel()
        removeBubble()
        val menuTarget = machine.last
        val rows = menuItems(menuTarget).map { item ->
            PetMenuView.Row(menuLabel(item), item = item, shortLabel = wheelLabel(item)) { if (alive(ticket)) onMenu(item, ticket, menuTarget) }
        }
        val view = PetMenuView(ctx, rows, onOutside = { if (alive(ticket)) dismissMenu(animated = true) }, motion = ::animationsOn)
        val params = baseParams(0, 0, WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
        if (!placeMenu(view, params)) return
        try {
            val manager = wm ?: return
            if (!installWindow(ticket, view, manager, params)) return
        } catch (e: Exception) {
            Log.w(TAG, "menu add failed", e)
            return
        }
        menuView = view
        menuParams = params
        if (rawX != null && rawY != null) view.beginPointer(rawX, rawY)
        scheduleMenuTimeout(ticket)
    }

    private fun placeMenu(view: PetMenuView, params: WindowManager.LayoutParams): Boolean {
        val ctx = hostContext ?: return false
        val pv = petView ?: return false
        val pp = petParams ?: return false
        val rest = restPos(pv, pp)
        val body = loaded?.frames?.body?.at(rest) ?: return false
        val g = PetWheelGeometry.fit(
            if (view.hosts) machine.last?.hosts?.size?.coerceAtLeast(1) ?: 1 else menuItems(machine.last).size,
            dock.side, body.centerX.toFloat(), body.centerY.toFloat(), safeArea(), ctx.resources.displayMetrics.density,
            if (view.hosts) 2f else ctx.resources.configuration.fontScale,
        )
        view.place(g)
        params.width = g.bounds.width
        params.height = g.bounds.height
        params.x = g.bounds.left
        params.y = g.bounds.top
        return true
    }

    private fun menuLabel(item: PetMenuItem): String = AppStrings.get(app, when (item) {
        PetMenuItem.ASK -> R.string.pet_ask
        PetMenuItem.NEW_CHAT -> R.string.pet_new_chat
        PetMenuItem.SCREENSHOT -> R.string.pet_screenshot
        PetMenuItem.STOP -> R.string.pet_stop
        PetMenuItem.SWITCH_HOST -> R.string.pet_switch_host
        PetMenuItem.SNOOZE -> R.string.pet_snooze
        PetMenuItem.SETTINGS -> R.string.pet_settings
    })

    private fun wheelLabel(item: PetMenuItem): String = AppStrings.get(app, when (item) {
        PetMenuItem.ASK -> R.string.pet_ask
        PetMenuItem.NEW_CHAT -> R.string.pet_new_chat
        PetMenuItem.SCREENSHOT -> R.string.pet_wheel_screenshot
        PetMenuItem.STOP -> R.string.pet_wheel_stop
        PetMenuItem.SWITCH_HOST -> R.string.pet_wheel_hosts
        PetMenuItem.SNOOZE -> R.string.pet_wheel_snooze
        PetMenuItem.SETTINGS -> R.string.pet_wheel_settings
    })

    private fun onMenu(item: PetMenuItem, ticket: OverlayWindowOwner.Ticket, target: PetPush?) {
        machine.onTouch(now())
        when (item) {
            PetMenuItem.ASK, PetMenuItem.NEW_CHAT -> {
                dismissMenu(animated = true)
                showAsk(fresh = item == PetMenuItem.NEW_CHAT)
            }
            PetMenuItem.SCREENSHOT -> captureScreenshot()
            PetMenuItem.STOP -> {
                dismissMenu(animated = true)
                callDart("stop", mapOf("host" to target?.host, "sessionId" to target?.sessionId)) { evaluate() }
            }
            PetMenuItem.SWITCH_HOST -> showHosts(ticket)
            PetMenuItem.SNOOZE -> {
                dismissMenu()
                snoozeToday()
            }
            PetMenuItem.SETTINGS -> {
                dismissMenu()
                pendingSettings = true
                open(null, null, null)
                if (started > 0) deliverSettings()
            }
        }
    }

    /** 切换主机: the menu's rows become the hosts, the one in use muted. */
    /** The rows this phone can offer: the accessibility screenshot exists from Android 11. */
    private fun menuItems(push: PetPush?) = PetMenuItem.of(push, screenshots = Build.VERSION.SDK_INT >= 30)

    private fun showHosts(ticket: OverlayWindowOwner.Ticket) {
        val view = menuView ?: return
        val params = menuParams ?: return
        view.setRows(machine.last?.hosts.orEmpty().map { h ->
            PetMenuView.Row(h.label, muted = h.current) {
                if (alive(ticket)) {
                    dismissMenu()
                    if (!h.current) callDart("switchHost", mapOf("host" to h.ref)) { evaluate() }
                }
            }
        })
        // Rows and buttons only match again once placed; a list that can't be shown goes.
        if (!placeMenu(view, params)) { dismissMenu(); return }
        update(view, params)
        scheduleMenuTimeout(ticket)
    }

    private fun scheduleMenuTimeout(ticket: OverlayWindowOwner.Ticket) {
        menuTimeout?.let(main::removeCallbacks)
        Runnable { if (alive(ticket)) dismissMenu() }.also {
            menuTimeout = it
            main.postDelayed(it, MENU_MS)
        }
    }

    private fun dismissMenu(animated: Boolean = false) {
        menuTimeout?.let(main::removeCallbacks)
        menuTimeout = null
        closingMenu?.let { remove(it) }
        closingMenu = null
        val view = menuView
        menuView = null
        menuParams = null
        if (view != null) {
            if (animated && animationsOn() && alive(windowTickets[view])) {
                closingMenu = view
                view.close {
                    remove(view)
                    if (closingMenu === view) closingMenu = null
                }
            } else remove(view)
        }
    }

    private fun captureScreenshot() {
        val ticket = petTicket
        if (!alive(ticket)) return
        val request = capture.begin() ?: return
        // No exit animation may remain in the image. INVISIBLE keeps the PET generation alive
        // for EntryActivity's authenticated openIntent without drawing or intercepting touches.
        dismissMenu()
        removeBubble()
        removeAsk()
        hideTarget()
        stopLoop()
        petView?.animate()?.cancel()
        petView?.visibility = View.INVISIBLE
        speaksForResults = false
        publishStatus()
        PetScreenshot.capture(app) { result ->
            val accepted = capture.finish(request) && alive(ticket) && interactive() && machine.enabled &&
                started == 0 && !machine.appForeground && !machine.snoozed(now()) &&
                // A phone task that began meanwhile keeps the screen; DSH Go doesn't jump in front of it.
                PhoneControl.peek()?.foregroundLine() == null
            val path = result.path
            if (accepted && path != null) {
                openIntent(EntryActivity.screenshot(app, path), null)
            } else {
                if (path != null) PetScreenshot.discard(app, path)
                if (accepted) Toast.makeText(app, AppStrings.get(app, when (result.error) {
                    PetScreenshot.Error.UNSUPPORTED -> R.string.pet_screenshot_unsupported
                    PetScreenshot.Error.ACCESSIBILITY_REQUIRED -> R.string.pet_screenshot_accessibility
                    PetScreenshot.Error.SECURE -> R.string.pet_screenshot_secure
                    PetScreenshot.Error.BUSY -> R.string.pet_screenshot_busy
                    PetScreenshot.Error.TIMEOUT -> R.string.pet_screenshot_timeout
                    else -> R.string.pet_screenshot_failed
                }), Toast.LENGTH_LONG).show()
            }
            evaluate()
        }
    }

    private fun deliverSettings() {
        if (!pendingSettings) return
        pendingSettings = false
        listener?.onOpenSettings()
    }

    private fun callDart(method: String, args: Any?, reply: (Any?) -> Unit) {
        val l = listener ?: return reply(null)
        l.call(method, args, reply)
    }

    // ---- 问一句 ---------------------------------------------------------------------------------

    private var askView: AskBarView? = null
    private var askParams: WindowManager.LayoutParams? = null

    /** Where the bar sits with the keyboard down. */
    private var askY = 0
    private var askFresh = false

    /** Dart's answer to `askTarget`; null until it comes. */
    private var askTarget: AskTarget? = null

    /** What was typed: kept when the bar closes, until it is sent. */
    private val askDraftState = PetAskDraft()
    private var askDraft: String
        get() = askDraftState.text
        set(value) { askDraftState.edit(value) }
    private var voiceHandoff = 0L
    private var pendingVoice = false
    private var returnAsk = false
    private var askRequest = 0L

    /**
     * Opens the input bar. [orb] is the floating ball's own strip: it grows out of the docked pet,
     * targets this phone's Orb conversation, and its send key becomes ■ while the strip's session
     * runs. Without [orb] it is the old beside-the-pet bar for the active host (TalkBack / wheel).
     */
    private fun showAsk(fresh: Boolean, orb: Boolean = false) {
        if (capture.inFlight || !interactive()) return
        removeAsk()
        val ctx = hostContext ?: return
        val pv = petView ?: return
        val f = loaded?.frames ?: return
        val ticket = windows.claim(OverlayWindowOwner.Role.ASK) ?: return
        askFresh = fresh
        askOrb = orb
        askTarget = null
        val view = AskBarView(ctx, askListener(ticket))
        view.input.setText(askDraft)
        view.setState(problem = true, sending = askDraftState.sending)
        view.input.setSelection(view.input.length())
        view.setTarget(
            if (orb) AppStrings.get(app, R.string.pet_orb_starting) else null,
            switchShown = false,
            on = fresh,
        )
        view.setStripLook(orb)
        view.setStatus(orbRun?.let { orbLine(it) }?.takeIf { it.isNotEmpty() }, error = orbRun?.state == "error")
        view.setStopMode(orb && orbRun?.canStop == true)
        val area = safeArea()
        val gap = ctx.dp(8f)
        val margin = edge(ctx)
        val at = EdgeSnap.position(dock, pv.reach, area, margin)
        val body = f.body.at(at)
        val pet = ScreenRect(at.x + pv.reach.left, body.top, at.x + pv.reach.right, body.bottom)
        val orbPlace = if (orb) orbStripPlacement() else null
        val w = if (orbPlace != null) {
            minOf(orbPlace.strip.width, ctx.dp(ASK_MAX_DP))
        } else {
            AskPlace.width(pet, dock.side, area, gap, margin, ctx.dp(ASK_MIN_DP), ctx.dp(ASK_MAX_DP))
        }
        val (_, h) = view.measureFor(w)
        val pos = if (orbPlace != null) {
            val x = if (dock.side == Side.RIGHT) orbPlace.strip.right - w else orbPlace.strip.left
            val y = (body.centerY - h / 2).coerceIn(
                area.top + margin,
                maxOf(area.top + margin, area.screenH - area.bottom - margin - h),
            )
            WindowPos(x, y)
        } else {
            AskPlace.beside(pet, dock.side, w, h, area, gap, margin)
        }
        // The one window that takes focus, and only while it is open: the keyboard needs it.
        val params = baseParams(w, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH).apply {
            flags = flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            x = pos.x
            y = pos.y
        }
        try {
            val manager = wm ?: return
            if (!installWindow(ticket, view, manager, params)) return
        } catch (e: Exception) {
            Log.w(TAG, "ask bar add failed", e)
            return
        }
        askView = view
        askParams = params
        askY = pos.y
        Log.i(TAG, "ask add orb=$orb w=$w h=$h x=${pos.x} y=${pos.y}")
        dismissMenu()
        removeBubble()
        if (orbPlace != null) {
            hideOrbStrip(animate = false)
            ensureOrbStrip()?.showSeam(orbPlace)
        }
        if (animationsOn()) {
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(150).setInterpolator(easing).start()
        }
        view.input.requestFocus()
        view.post { if (askView === view) view.context.getSystemService(InputMethodManager::class.java)?.showSoftInput(view.input, 0) }
        syncBookmarks()
        evaluate()
        requestAskTarget(view)
    }

    /** Asks Dart where a send would go; 「新对话」 re-asks so the capture flag follows it too. */
    private fun requestAskTarget(view: AskBarView) {
        val request = ++askRequest
        callDart("askTarget", mapOf("fresh" to askFresh, "orb" to askOrb)) { raw ->
            if (request != askRequest || askView !== view) return@callDart
            // No answer: nothing known to stand in the way; a send hands the text to the app.
            askTarget = AskTarget.fromMap(raw) ?: AskTarget(null, null, null, AskTarget.VOICE_OFF)
            showTarget(view)
        }
    }

    private fun showTarget(view: AskBarView) {
        val t = askTarget ?: return
        val fresh = askFresh || t.current == null
        view.setTarget(t.chat(fresh)?.line ?: AppStrings.get(app, R.string.pet_new_chat), t.canSwitch, fresh)
        // The orb input bar keeps the live run line under the input instead of hiding it.
        val run = if (askOrb) orbRun else null
        val status = t.problem ?: run?.let { orbLine(it) }
        view.setStatus(status?.takeIf { it.isNotEmpty() }, error = t.problem != null || run?.state == "error")
        view.setState(problem = t.problem != null)
        view.setStopMode(askOrb && run?.canStop == true)
    }

    private fun askListener(ticket: OverlayWindowOwner.Ticket) = object : AskBarView.Listener {
        override fun onSend(text: String) {
            Log.i(TAG, "ask send tap text=${text.length}")
            if (alive(ticket)) sendAsk(text)
        }

        override fun onSwitch() {
            if (!alive(ticket) || askDraftState.sending) return
            askFresh = !askFresh
            askView?.let(::showTarget)
            askView?.let(::requestAskTarget)
        }

        override fun onStop() {
            if (alive(ticket)) onOrbStop()
        }

        override fun onMic(down: Boolean, cancelled: Boolean) {
            if (!alive(ticket)) return
            if (down) micDown() else micUp(cancelled)
        }

        override fun onDraft(text: String) {
            if (alive(ticket)) {
                askDraft = text
                prefs.edit().putString("ask_draft", text).apply()
            }
        }

        override fun onClose() {
            if (!alive(ticket)) return
            Log.i(TAG, "ask close")
            removeAsk()
            evaluate()
        }

        override fun onIme(overlap: Int, visible: Boolean) {
            Log.i(TAG, "ask ime overlap=$overlap visible=$visible")
            if (alive(ticket)) liftAsk(overlap, visible)
        }
    }

    /** Above the keyboard while it covers the bar, back in place once it goes. */
    private fun liftAsk(overlap: Int, visible: Boolean) {
        val v = askView ?: return
        val p = askParams ?: return
        val ctx = hostContext ?: return
        // The bar leaves the pet while the keyboard is up; the seam would hang in the air.
        if (overlap > 0) orbStrip?.hideSeam() else if (askOrb) orbStripPlacement()?.let { orbStrip?.showSeam(it) }
        // Use the laid-out position, not params.y (which may already contain a pending move).
        val location = IntArray(2).also(v::getLocationOnScreen)
        val y = when {
            overlap > 0 -> AskPlace.lift(askY, location[1], overlap, ctx.dp(8f), safeArea(), edge(ctx))
            visible -> p.y
            else -> askY
        }
        if (y == p.y) return
        p.y = y
        update(v, p)
        syncBookmarks()
    }

    /**
     * Sends through Dart's own send path. Sent: the bar goes and the pet thinks. Refused (the
     * engine is off, no host, the send failed): the bar says why and keeps the text. No Dart to
     * answer: the app comes up with the text in a new chat's composer, unsent.
     */
    private fun sendAsk(text: String) {
        val view = askView ?: return
        val send = askDraftState.beginSend(text) ?: return
        machine.onTouch(now())
        val fresh = askFresh || askTarget?.current == null
        val chat = askTarget?.chat(fresh)
        val offer = askTarget?.offer
        view.setState(sending = true)
        val request = askRequest
        val deliver: (String?) -> Unit = { screenshot ->
            deliverAsk(view, offer, text, send, fresh, chat, request, screenshot)
        }
        if (askTarget?.shot == true) {
            view.setStatus(AppStrings.get(app, R.string.pet_ask_capturing))
            captureAskScreenshot(view, deliver)
        } else {
            view.setStatus(AppStrings.get(app, R.string.pet_ask_sending))
            deliver(null)
        }
    }

    /** Hides the bar and pet, captures the screen behind them, then sends the text with it. */
    private fun captureAskScreenshot(view: AskBarView, reply: (String?) -> Unit) {
        val request = capture.begin()
        if (request == null) {
            reply(null)
            return
        }
        view.visibility = View.INVISIBLE
        view.input.clearFocus()
        view.context.getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(view.windowToken, 0)
        hideTarget()
        orbStrip?.hideSeam()
        hideBookmarks()
        petView?.visibility = View.INVISIBLE
        publishStatus()
        main.postDelayed({
            PetScreenshot.capture(app) { result ->
                val accepted = capture.finish(request)
                if (askView === view) view.visibility = View.VISIBLE
                petView?.visibility = View.VISIBLE
                if (askOrb) orbStripPlacement()?.let { orbStrip?.showSeam(it) }
                evaluate()
                reply(if (accepted) result.path else null)
            }
        }, ASK_SHOT_DELAY_MS)
    }

    private fun deliverAsk(
        view: AskBarView,
        offer: Long?,
        text: String,
        send: PetAskDraft.Send,
        fresh: Boolean,
        chat: AskTarget.AskChat?,
        request: Long,
        screenshot: String?,
    ) {
        val args = mapOf(
            "offer" to offer,
            "text" to text,
            "fresh" to (chat?.sessionId == null),
            "sessionId" to chat?.sessionId,
            "host" to chat?.host,
            "screenshot" to screenshot,
        )
        callDart("ask", args) { raw ->
            val r = raw as? Map<*, *>
            val completion = askDraftState.finish(send, accepted = r?.get("sent") == true) ?: return@callDart
            val current = request == askRequest && askView === view
            askView?.setState(sending = false)
            val orbSent = r?.get("orb") == true
            if (orbSent) {
                voiceSession = r["sessionId"] as? String
                voiceHost = r["host"] as? String
            }
            when {
                r?.get("sent") == true -> {
                    Log.i(TAG, "ask sent (${if (chat?.sessionId == null) "new chat" else "same chat"}) orb=$orbSent")
                    if (completion.cleared) {
                        // A reopened bar shares this draft. Clear its copy too so closing it
                        // cannot resurrect text the host already accepted.
                        askView?.input?.setText("")
                        prefs.edit().remove("ask_draft").apply()
                        if (current) removeAsk()
                    }
                    if (orbSent) {
                        if (askView != null && askOrb) {
                            askView?.setStatus(AppStrings.get(app, R.string.pet_voice_sent))
                        } else {
                            showSentStrip(screenshot != null)
                        }
                    }
                }
                r != null -> if (current) {
                    view.setState(sending = false)
                    view.setStatus((r["problem"] as? String) ?: AppStrings.get(app, R.string.pet_ask_failed), error = true)
                }
                // Closed meanwhile, the text stays the bar's draft (removeAsk kept it).
                current -> {
                    removeAsk()
                    openIntent(EntryActivity.newChat(app).putExtra(EntryActivity.EXTRA_TEXT, text), null)
                }
            }
            evaluate()
        }
    }

    private fun removeAsk() {
        askRequest++
        stopRecording()
        askView?.let { v ->
            askDraft = v.input.text?.toString() ?: askDraft
            remove(v)
        }
        askView = null
        askParams = null
        askTarget = null
        askOrb = false
        orbStrip?.hideSeam()
        Log.i(TAG, "ask remove")
        if (initialized) syncOrbStrip()
        syncBookmarks()
    }

    // ---- 悬浮球：长按语音 + 长条（v0.1 P0） ------------------------------------------------------

    private var orbVoice: OrbVoiceController? = null
    private var voiceCapsule: OrbVoiceCapsule? = null
    private var voiceTarget: AskTarget? = null
    private var voiceWav: ByteArray? = null
    private var voiceStartY = 0f
    private var voiceCancel = false
    private var voiceTimer: Runnable? = null
    private var voiceProblemGen = 0
    private var voiceOfferRequest = 0L
    private var voiceSession: String? = null
    private var voiceHost: String? = null
    private var orbStrip: OrbStrip? = null
    private var stripHide: Runnable? = null
    private var orbBookmarks: OrbBookmarks? = null
    private var bookmarkItems: List<OrbBookmark> = emptyList()
    private var bookmarkProbeLoop: Runnable? = null
    private var probeVariant = SeamVariant.TONGUE
    private var probeCycle: Runnable? = null

    /** The local Orb conversation's live status, pushed by Dart (`pushOrb`). */
    private data class OrbRun(
        val sessionId: String,
        val host: String,
        val running: Boolean,
        val canStop: Boolean,
        val state: String,
        val tool: String?,
        val text: String?,
    )

    private var orbRun: OrbRun? = null
    private var orbWasRunning = false
    private var orbDoneTimer: Runnable? = null
    private var orbStoppedAt = 0L
    private var orbStoppedSession: String? = null
    /** The open input bar is the orb's strip (not the old beside-the-pet bar). */
    private var askOrb = false

    /** Long-press on the ball: record now, ask Dart where a send would go in parallel. */
    private fun startVoice(rawX: Float, rawY: Float) {
        if (capture.inFlight || !interactive() || orbVoice != null) return
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            showVoiceHint(AppStrings.get(app, R.string.pet_ask_mic_permission))
            return
        }
        val pv = petView ?: return
        val pp = petParams ?: return
        val frames = loaded?.frames ?: return
        val capsule = ensureVoiceCapsule() ?: return
        dismissMenu()
        removeBubble()
        removeAsk()
        hideOrbStrip()
        machine.onTouch(now())
        voiceCancel = false
        voiceStartY = rawY
        voiceWav = null
        voiceTarget = null
        voiceSession = null
        voiceHost = null
        voiceProblemGen++
        val controller = OrbVoiceController(app)
        val listener = object : OrbVoiceController.Listener {
            override fun onLevel(level: Double) {
                capsule.setLevel(level)
            }

            override fun onLimit() {
                endVoice(cancelled = false)
            }

            override fun onError() {
                voiceProblem(AppStrings.get(app, R.string.pet_ask_not_heard))
            }

            override fun onSilenced() {
                voiceProblem(AppStrings.get(app, R.string.pet_voice_silenced))
            }
        }
        if (!controller.start(listener)) {
            showVoiceHint(AppStrings.get(app, R.string.pet_ask_mic_failed))
            return
        }
        orbVoice = controller
        capsule.show(frames.body.at(restPos(pv, pp)), safeArea(), animationsOn())
        capsule.setElapsed(0)
        capsule.setHint(AppStrings.get(app, R.string.pet_voice_hint))
        scheduleVoiceTimer()
        val request = ++voiceOfferRequest
        callDart("askTarget", mapOf("fresh" to false, "orb" to true)) { raw ->
            if (request != voiceOfferRequest) return@callDart
            val target = AskTarget.fromMap(raw)
            if (target == null) {
                voiceProblem(AppStrings.get(app, R.string.pet_ask_failed))
                return@callDart
            }
            if (target.problem != null) {
                voiceProblem(target.problem)
                return@callDart
            }
            if (target.voice != AskTarget.VOICE_READY) {
                voiceProblem(
                    AppStrings.get(
                        app,
                        if (target.voice == AskTarget.VOICE_MISSING) R.string.pet_ask_voice_missing else R.string.pet_ask_voice_off,
                    ),
                )
                return@callDart
            }
            voiceTarget = target
            // The finger may have been released before Dart answered: carry the held WAV on.
            voiceWav?.let {
                voiceWav = null
                transcribeVoice(it, target)
            }
        }
        Log.i(TAG, "orb voice: hold start y=$rawY dock=${dock.side}")
    }

    private fun moveVoice(rawY: Float) {
        val controller = orbVoice ?: return
        val threshold = VOICE_CANCEL_DP * app.resources.displayMetrics.density
        val cancel = voiceStartY - rawY >= threshold
        if (cancel == voiceCancel) return
        voiceCancel = cancel
        voiceCapsule?.setHint(
            AppStrings.get(app, if (cancel) R.string.pet_voice_cancel else R.string.pet_voice_hint),
            danger = cancel,
        )
        Log.i(TAG, "orb voice: slide dy=${(voiceStartY - rawY).toInt()} cancel=$cancel elapsed=${controller.elapsedMs}")
    }

    private fun endVoice(cancelled: Boolean) {
        val controller = orbVoice ?: return
        orbVoice = null
        stopVoiceTimer()
        val elapsed = controller.elapsedMs
        val cancel = cancelled || voiceCancel
        Log.i(TAG, "orb voice: release elapsed=${elapsed}ms cancel=$cancel")
        if (cancel) {
            controller.cancel()
            voiceCapsule?.hide()
            resetVoice()
            return
        }
        if (elapsed < VOICE_MIN_MS) {
            controller.cancel()
            voiceProblem(AppStrings.get(app, R.string.pet_voice_too_short))
            return
        }
        voiceCapsule?.setHint(AppStrings.get(app, R.string.pet_ask_transcribing))
        controller.finish { wav -> onVoiceWav(wav) }
    }

    private fun onVoiceWav(wav: ByteArray?) {
        if (wav == null || wav.size < OrbVoiceController.MIN_WAV_BYTES) {
            voiceProblem(AppStrings.get(app, R.string.pet_ask_not_heard))
            return
        }
        val target = voiceTarget
        if (target == null) {
            voiceWav = wav
            Log.i(TAG, "orb voice: wav held (${wav.size}B) until target")
            return
        }
        transcribeVoice(wav, target)
    }

    private fun transcribeVoice(wav: ByteArray, target: AskTarget) {
        val started = System.currentTimeMillis()
        callDart("transcribe", mapOf("wav" to wav)) { raw ->
            val r = raw as? Map<*, *>
            val text = (r?.get("text") as? String)?.trim()
            Log.i(TAG, "orb voice: asr ${System.currentTimeMillis() - started}ms text=${text?.length ?: 0}")
            if (text.isNullOrEmpty()) {
                voiceProblem((r?.get("problem") as? String) ?: AppStrings.get(app, R.string.pet_ask_not_heard))
                return@callDart
            }
            sendVoiceText(text, target)
        }
    }

    private fun sendVoiceText(text: String, target: AskTarget) {
        val fresh = target.current == null
        val chat = target.chat(fresh)
        val deliver: (String?) -> Unit = { path -> deliverVoice(text, target, fresh, chat, path) }
        if (target.shot) {
            voiceCapsule?.setHint(AppStrings.get(app, R.string.pet_ask_capturing))
            captureOrbShot(deliver)
        } else {
            voiceCapsule?.setHint(AppStrings.get(app, R.string.pet_ask_sending))
            deliver(null)
        }
    }

    /** Hides the pet and the orb layers, captures what is behind them, then restores. */
    private fun captureOrbShot(reply: (String?) -> Unit) {
        val request = capture.begin()
        if (request == null) {
            reply(null)
            return
        }
        voiceCapsule?.hide()
        hideOrbStrip(false)
        hideBookmarks()
        dismissMenu()
        removeBubble()
        hideTarget()
        petView?.visibility = View.INVISIBLE
        publishStatus()
        main.postDelayed({
            PetScreenshot.capture(app) { result ->
                val accepted = capture.finish(request)
                petView?.visibility = View.VISIBLE
                evaluate()
                if (!accepted && result.path != null) PetScreenshot.discard(app, result.path)
                reply(if (accepted) result.path else null)
            }
        }, ASK_SHOT_DELAY_MS)
    }

    private fun deliverVoice(
        text: String,
        target: AskTarget,
        fresh: Boolean,
        chat: AskTarget.AskChat?,
        screenshot: String?,
    ) {
        val args = mapOf(
            "offer" to target.offer,
            "text" to text,
            "fresh" to (chat?.sessionId == null),
            "sessionId" to chat?.sessionId,
            "host" to chat?.host,
            "screenshot" to screenshot,
        )
        callDart("ask", args) { raw ->
            val r = raw as? Map<*, *>
            if (r?.get("sent") == true) {
                voiceSession = r["sessionId"] as? String
                voiceHost = r["host"] as? String
                val orbSent = r["orb"] == true
                Log.i(TAG, "orb voice: sent session=$voiceSession shot=${screenshot != null} orb=$orbSent")
                voiceCapsule?.hide()
                resetVoice()
                if (orbSent) {
                    showSentStrip(screenshot != null)
                } else {
                    showVoiceHint(AppStrings.get(app, R.string.pet_voice_sent))
                }
            } else {
                voiceProblem((r?.get("problem") as? String) ?: AppStrings.get(app, R.string.pet_ask_failed))
            }
        }
    }

    private fun showVoiceHint(message: String) {
        Toast.makeText(app, message, Toast.LENGTH_LONG).show()
    }

    private fun voiceProblem(message: String) {
        orbVoice?.cancel()
        orbVoice = null
        stopVoiceTimer()
        voiceTarget = null
        voiceWav = null
        voiceOfferRequest++
        val capsule = ensureVoiceCapsule()
        // A capture hid the capsule: bring it back for the error instead of failing silently.
        if (capsule != null && !capsule.visible && !machine.appForeground) {
            val pv = petView
            val pp = petParams
            val frames = loaded?.frames
            if (pv != null && pp != null && frames != null) {
                capsule.show(frames.body.at(restPos(pv, pp)), safeArea(), false)
            }
        }
        val shown = capsule?.visible == true
        capsule?.setHint(message, danger = true)
        if (!shown) showVoiceHint(message)
        val gen = ++voiceProblemGen
        main.postDelayed({ if (gen == voiceProblemGen && orbVoice == null) capsule?.hide() }, 1_600)
        Log.i(TAG, "orb voice: problem $message")
    }

    private fun resetVoice() {
        voiceTarget = null
        voiceWav = null
        voiceCancel = false
        voiceOfferRequest++
        voiceProblemGen++
    }

    private fun scheduleVoiceTimer() {
        stopVoiceTimer()
        val r = object : Runnable {
            override fun run() {
                val v = orbVoice ?: return
                voiceCapsule?.setElapsed(v.elapsedMs)
                main.postDelayed(this, 100)
            }
        }
        voiceTimer = r
        main.postDelayed(r, 100)
    }

    private fun stopVoiceTimer() {
        voiceTimer?.let(main::removeCallbacks)
        voiceTimer = null
    }

    private fun cancelVoiceAndStrip() {
        stopVoiceTimer()
        orbVoice?.cancel()
        orbVoice = null
        voiceTarget = null
        voiceWav = null
        voiceOfferRequest++
        voiceProblemGen++
        voiceCapsule?.hide()
        hideOrbStrip(animate = false)
    }

    private fun discardOrbLayers() {
        cancelVoiceAndStrip()
        orbStrip?.hide(animate = false)
        orbStrip = null
        voiceCapsule = null
        orbBookmarks?.hide()
        orbBookmarks = null
        bookmarkItems = emptyList()
        bookmarkProbeLoop?.let(main::removeCallbacks)
        bookmarkProbeLoop = null
        probeCycle?.let(main::removeCallbacks)
        probeCycle = null
        stripHide?.let(main::removeCallbacks)
        stripHide = null
    }

    private fun ensureOrbStrip(): OrbStrip? {
        orbStrip?.let { return it }
        val ctx = hostContext ?: return null
        val s = OrbStrip(
            ctx = ctx,
            windows = windows,
            params = { w, h, flags, behind -> orbStripParams(w, h, flags, behind) },
            add = { ticket, view, p ->
                // A tuck variant asks for an application overlay under the accessibility host:
                // that window must come from the app's own window context.
                val manager = if (p.type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY && host == OverlayHost.ACCESSIBILITY) {
                    appWindowContext().getSystemService(WindowManager::class.java)
                } else {
                    wm
                }
                runCatching { manager?.let { installWindow(ticket, view, it, p) } ?: false }.getOrDefault(false)
            },
            update = { v, p -> update(v, p) },
            remove = { v -> remove(v) },
            onStop = { onOrbStop() },
        )
        orbStrip = s
        return s
    }

    private fun ensureVoiceCapsule(): OrbVoiceCapsule? {
        voiceCapsule?.let { return it }
        val ctx = hostContext ?: return null
        val c = OrbVoiceCapsule(
            ctx = ctx,
            windows = windows,
            params = { w, h, flags -> baseParams(w, h, flags) },
            add = { ticket, view, p ->
                runCatching { wm?.let { installWindow(ticket, view, it, p) } ?: false }.getOrDefault(false)
            },
            update = { v, p -> update(v, p) },
            remove = { v -> remove(v) },
        )
        voiceCapsule = c
        return c
    }

    private fun orbStripParams(w: Int, h: Int, flags: Int, behind: Boolean): WindowManager.LayoutParams {
        val p = baseParams(w, h, flags)
        if (behind) {
            if (host == OverlayHost.ACCESSIBILITY) {
                p.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                Log.i(TAG, "strip: application overlay under accessibility (behind)")
            } else {
                Log.w(TAG, "strip: tuck on application host; z-order may put the strip over the pet")
            }
        }
        return p
    }

    private fun orbStripPlacement(variant: SeamVariant = SeamVariant.TONGUE): StripPlacement? {
        val ctx = hostContext ?: return null
        val pv = petView ?: return null
        val pp = petParams ?: return null
        val frames = loaded?.frames ?: return null
        val rest = restPos(pv, pp)
        return OrbStripGeometry.place(
            safeArea(),
            frames.body.at(rest),
            pv.reach.at(rest),
            dock.side,
            variant,
            ctx.resources.displayMetrics.density,
        )
    }

    private fun showSentStrip(withShot: Boolean) {
        if (machine.appForeground) return
        // The bridge usually beats this placeholder: if the run is already known,
        // show its live line/stop instead of overwriting it with 「已发送」.
        if (orbRun?.running == true || orbRun?.state == "waiting-approval" || orbRun?.state == "waiting-question") {
            syncOrbStrip()
            return
        }
        val strip = ensureOrbStrip() ?: return
        val place = orbStripPlacement() ?: return
        orbStoppedAt = 0
        orbStoppedSession = null
        val line = AppStrings.get(app, R.string.pet_voice_sent) +
            if (withShot) " · " + AppStrings.get(app, R.string.pet_voice_shot) else ""
        strip.setStopVisible(true)
        strip.setDot(OverlayPalette.of(hostContext ?: app).accent)
        strip.show(place, line, animationsOn())
        stripHide?.let(main::removeCallbacks)
        val hide = Runnable { if (orbStrip === strip) strip.hide(animate = true) }
        stripHide = hide
        main.postDelayed(hide, SENT_STRIP_MS)
        Log.i(TAG, "orb voice: sent strip ${strip.describe()}")
    }

    private fun onOrbStop() {
        val run = orbRun
        val sid = run?.sessionId ?: voiceSession
        val host = run?.host ?: voiceHost
        Log.i(TAG, "orb strip: stop session=$sid")
        if (sid != null) {
            callDart("orbStop", mapOf("host" to host, "sessionId" to sid)) { ok ->
                Log.i(TAG, "orb strip: stop result=$ok")
            }
        }
        orbStoppedAt = now()
        orbStoppedSession = sid
        orbRun = run?.copy(running = false, canStop = false, state = "done")
        orbWasRunning = false
        val strip = orbStrip ?: return
        strip.setStopVisible(false)
        strip.setDot(OverlayPalette.of(hostContext ?: app).muted)
        strip.setLine(AppStrings.get(app, R.string.pet_orb_stopped))
        stripHide?.let(main::removeCallbacks)
        stripHide = null
        orbDoneTimer?.let(main::removeCallbacks)
        orbDoneTimer = null
        main.postDelayed({ if (orbStrip === strip) strip.hide(animate = true) }, 1_200)
    }

    private fun hideOrbStrip(animate: Boolean = true) {
        stripHide?.let(main::removeCallbacks)
        stripHide = null
        orbDoneTimer?.let(main::removeCallbacks)
        orbDoneTimer = null
        orbStrip?.hide(animate)
        syncBookmarks()
    }

    // ---- 长条：悬浮球会话的实时状态 --------------------------------------------------------------

    /** Dart's `pushOrb`: the local Orb conversation's running state, tool and stop availability. */
    internal fun pushOrb(raw: Map<*, *>) {
        if (raw["visible"] != true) {
            orbRun = null
            orbWasRunning = false
            orbStoppedAt = 0
            hideOrbStrip(animate = true)
            if (askOrb) {
                askView?.setStatus(null)
                askView?.setStopMode(false)
            }
            return
        }
        val sessionId = raw["sessionId"] as? String ?: return
        val host = raw["host"] as? String ?: return
        // A cancel's turn-end can lag the stale running frame; keep it out.
        if (raw["running"] == true && sessionId == orbStoppedSession && now() - orbStoppedAt < 5_000) {
            Log.i(TAG, "orb strip: ignore stale running push after stop")
            return
        }
        val run = OrbRun(
            sessionId = sessionId,
            host = host,
            running = raw["running"] == true,
            canStop = raw["canStop"] == true,
            state = raw["state"] as? String ?: "done",
            tool = raw["tool"] as? String,
            text = raw["text"] as? String,
        )
        orbRun = run
        if (askOrb) {
            askView?.setStatus(orbLine(run), error = run.state == "error")
            askView?.setStopMode(run.canStop)
        }
        syncOrbStrip()
        syncBookmarks()
    }

    /** Shows/updates/hides the status strip from [orbRun] and the current foreground. */
    private fun syncOrbStrip() {
        val run = orbRun
        if (!initialized || capture.inFlight || machine.appForeground || !interactive() || petView == null) {
            hideOrbStrip(animate = false)
            return
        }
        // The input bar is the strip while it types; it carries the same status line.
        if (askView != null && askOrb) return
        if (run == null) {
            hideOrbStrip(animate = true)
            orbWasRunning = false
            return
        }
        val waiting = run.state == "waiting-approval" || run.state == "waiting-question"
        if (run.running || waiting) {
            orbDoneTimer?.let(main::removeCallbacks)
            orbDoneTimer = null
            stripHide?.let(main::removeCallbacks)
            stripHide = null
            showOrbStatus(run, done = false, error = false)
            orbWasRunning = true
            return
        }
        if (run.state == "error") {
            showOrbStatus(run, done = false, error = true)
            orbWasRunning = false
            scheduleOrbHide(4_000)
            return
        }
        // A stop key just acted: keep 「已停止」 rather than flipping to 「完成」.
        if (now() - orbStoppedAt < 3_000) {
            orbWasRunning = false
            return
        }
        if (orbWasRunning) {
            showOrbStatus(run, done = true, error = false)
            orbWasRunning = false
            scheduleOrbHide(3_000)
        } else {
            hideOrbStrip(animate = true)
        }
    }

    private fun showOrbStatus(run: OrbRun, done: Boolean, error: Boolean) {
        val strip = ensureOrbStrip() ?: return
        val place = orbStripPlacement() ?: return
        val palette = OverlayPalette.of(hostContext ?: app)
        strip.setStopVisible(run.canStop && !done && !error)
        strip.setDot(
            when {
                error -> palette.danger
                done -> 0xFF2E7D32.toInt()
                run.state == "waiting-approval" || run.state == "waiting-question" -> 0xFFE0A400.toInt()
                else -> palette.accent
            },
        )
        val line = orbLine(run, done, error)
        strip.show(place, line, animate = !strip.visible)
        Log.i(TAG, "orb strip: ${run.state} line=$line stop=${run.canStop} ${strip.describe()}")
        syncBookmarks()
    }

    private fun scheduleOrbHide(ms: Long) {
        orbDoneTimer?.let(main::removeCallbacks)
        val r = Runnable { hideOrbStrip(animate = true) }
        orbDoneTimer = r
        main.postDelayed(r, ms)
    }

    private fun orbLine(run: OrbRun?, done: Boolean = false, error: Boolean = false): String {
        if (run == null) return ""
        if (error) {
            val reason = run.text?.trim()?.takeIf { it.isNotEmpty() }?.take(40)
            return AppStrings.get(app, R.string.pet_orb_failed) + if (reason != null) "：$reason" else ""
        }
        if (done) return AppStrings.get(app, R.string.pet_orb_done)
        return when (run.state) {
            "waiting-approval" -> AppStrings.get(app, R.string.pet_orb_waiting_approval)
            "waiting-question" -> AppStrings.get(app, R.string.pet_orb_waiting_question)
            "error" -> AppStrings.get(app, R.string.pet_orb_failed)
            else -> orbToolLine(run.tool)
        }
    }

    private fun orbToolLine(tool: String?): String {
        val name = tool.orEmpty().lowercase()
        val res = when {
            name.contains("screen") -> R.string.pet_orb_screen
            name.contains("tap") || name.contains("click") -> R.string.pet_orb_tap
            name.contains("type") || name.contains("input") || name.contains("text") -> R.string.pet_orb_type
            name.contains("scroll") || name.contains("swipe") -> R.string.pet_orb_scroll
            name.contains("bash") || name.contains("shell") || name.contains("terminal") -> R.string.pet_orb_shell
            name.contains("web") || name.contains("search") || name.contains("fetch") -> R.string.pet_orb_web
            name.contains("background_agent") -> R.string.pet_orb_background
            name.contains("device_agent") -> R.string.pet_orb_device
            else -> R.string.pet_orb_working
        }
        return AppStrings.get(app, res)
    }

    // ---- 书签：本机后台 + 跨设备任务 ------------------------------------------------------------

    /** Dart's `pushBookmarks`; the items survive a hidden strip and reappear with it. */
    internal fun pushBookmarks(raw: Map<*, *>) {
        val visible = raw["visible"] == true
        bookmarkItems = if (!visible) {
            emptyList()
        } else {
            (raw["items"] as? List<*>).orEmpty().mapNotNull { item ->
                val m = item as? Map<*, *> ?: return@mapNotNull null
                val id = m["id"] as? String ?: return@mapNotNull null
                val sessionId = m["sessionId"] as? String ?: return@mapNotNull null
                OrbBookmark(
                    id = id,
                    kind = (m["kind"] as? String) ?: "background",
                    sessionId = sessionId,
                    nodeId = m["nodeId"] as? String,
                    title = (m["title"] as? String)?.takeIf { it.isNotBlank() } ?: AppStrings.get(app, R.string.pet_orb_task),
                    status = (m["status"] as? String) ?: "running",
                    startedAt = (m["startedAt"] as? Number)?.toLong() ?: now(),
                    endedAt = (m["endedAt"] as? Number)?.toLong(),
                    unread = m["unread"] == true,
                    deviceName = m["deviceName"] as? String,
                )
            }
        }
        Log.i(TAG, "bookmarks push visible=$visible items=${bookmarkItems.size}")
        syncBookmarks()
    }

    private fun syncBookmarks() {
        val anchor = if (
            initialized && !capture.inFlight && !machine.appForeground && interactive() &&
            petView != null && bookmarkItems.isNotEmpty()
        ) {
            bookmarkAnchor()
        } else {
            null
        }
        if (anchor == null) {
            orbBookmarks?.hide()
            return
        }
        val bookmarks = ensureBookmarks() ?: return
        bookmarks.show(bookmarkItems, anchor, safeArea(), dock.side, animate = !bookmarks.visible)
    }

    private fun bookmarkAnchor(): ScreenRect? {
        val strip = orbStrip
        if (strip?.visible == true) strip.current?.strip?.let { return it }
        val v = askView
        val p = askParams
        if (v != null && p != null) {
            val h = if (v.height > 0) v.height else v.measuredHeight
            if (h > 0 && p.width > 0) return ScreenRect(p.x, p.y, p.x + p.width, p.y + h)
        }
        return null
    }

    private fun ensureBookmarks(): OrbBookmarks? {
        orbBookmarks?.let { return it }
        val ctx = hostContext ?: return null
        val b = OrbBookmarks(
            ctx = ctx,
            windows = windows,
            params = { w, h -> baseParams(w, h, 0) },
            add = { ticket, view, p ->
                runCatching { wm?.let { installWindow(ticket, view, it, p) } ?: false }.getOrDefault(false)
            },
            update = { v, p -> update(v, p) },
            remove = { v -> remove(v) },
            onOpen = { openBookmark(it) },
            onDismiss = { dismissBookmark(it) },
            onOverflow = { onBookmarkOverflow() },
        )
        orbBookmarks = b
        return b
    }

    private fun openBookmark(b: OrbBookmark) {
        Log.i(TAG, "bookmark open ${b.id} kind=${b.kind} session=${b.sessionId}")
        callDart("bookmarkOpen", mapOf("id" to b.id)) { }
        if (b.kind == "device" && b.nodeId != null) {
            openIntent(
                EntryActivity.openMesh(app, b.nodeId, b.sessionId, b.title, b.deviceName ?: b.nodeId),
                null,
            )
        } else {
            open(b.sessionId, LOCAL_HOST, b.title)
        }
    }

    private fun dismissBookmark(b: OrbBookmark) {
        Log.i(TAG, "bookmark dismiss ${b.id}")
        callDart("bookmarkDismiss", mapOf("id" to b.id)) { }
        bookmarkItems = bookmarkItems.filterNot { it.id == b.id }
        syncBookmarks()
    }

    private fun onBookmarkOverflow() {
        openIntent(EntryActivity.openApp(app), null)
    }

    private fun hideBookmarks() {
        orbBookmarks?.hide()
    }

    // ---- E1/E5 probe（debug builds only; PetOverlayProbe forwards `cmd strip`） ------------------

    /** Debug: the strip's stop key without finding its pixels on screen. */
    internal fun probeStop() {
        Log.i(TAG, "probe stop")
        onOrbStop()
    }

    /** Debug: a fake strip + bookmark rows for the P3 visual check. */
    internal fun probeBookmarks(show: Boolean) {
        bookmarkProbeLoop?.let(main::removeCallbacks)
        bookmarkProbeLoop = null
        if (!show) {
            bookmarkItems = emptyList()
            orbRun = null
            orbWasRunning = false
            hideBookmarks()
            hideOrbStrip(animate = false)
            return
        }
        if (!initialized || petView == null) return
        val run = OrbRun("probe-bg", LOCAL_HOST, true, true, "running", null, null)
        val fake = mapOf(
            "visible" to true,
            "items" to listOf(
                mapOf(
                    "id" to "bg:probe-1", "kind" to "background", "sessionId" to "probe-1",
                    "title" to "整理照片", "status" to "running",
                    "startedAt" to now() - 32_000, "unread" to false,
                ),
                mapOf(
                    "id" to "bg:probe-2", "kind" to "background", "sessionId" to "probe-2",
                    "title" to "查资料", "status" to "completed",
                    "startedAt" to now() - 600_000, "endedAt" to now() - 300_000, "unread" to true,
                ),
                mapOf(
                    "id" to "dev:probe-node:probe-3", "kind" to "device", "sessionId" to "probe-3",
                    "nodeId" to "probe-node", "title" to "改文档", "status" to "completed",
                    "startedAt" to now() - 900_000, "endedAt" to now() - 800_000, "unread" to true,
                    "deviceName" to "书房 Mac",
                ),
                mapOf(
                    "id" to "dev:probe-node:probe-4", "kind" to "device", "sessionId" to "probe-4",
                    "nodeId" to "probe-node", "title" to "编译", "status" to "running",
                    "startedAt" to now() - 20_000, "unread" to false, "deviceName" to "win32-asus",
                ),
                mapOf(
                    "id" to "bg:probe-5", "kind" to "background", "sessionId" to "probe-5",
                    "title" to "第五个", "status" to "running",
                    "startedAt" to now() - 10_000, "unread" to false,
                ),
            ),
        )
        val loop = object : Runnable {
            private var left = 20

            override fun run() {
                if (left-- <= 0) {
                    bookmarkProbeLoop = null
                    return
                }
                orbRun = run
                showOrbStatus(run, done = false, error = false)
                pushBookmarks(fake)
                main.postDelayed(this, 500)
            }
        }
        bookmarkProbeLoop = loop
        loop.run()
    }

    /** Debug: send a text to the local orb exactly as the input bar/voice would (no UI). */
    internal fun probeSay(text: String) {
        if (!initialized || !interactive()) {
            Log.i(TAG, "probe say: no pet")
            return
        }
        callDart("askTarget", mapOf("fresh" to false, "orb" to true)) { raw ->
            val target = AskTarget.fromMap(raw)
            if (target == null) {
                Log.i(TAG, "probe say: no target")
                return@callDart
            }
            if (target.problem != null) {
                voiceProblem(target.problem)
                return@callDart
            }
            val fresh = target.current == null
            val chat = target.chat(fresh)
            Log.i(TAG, "probe say: fresh=$fresh session=${chat?.sessionId} offer=${target.offer}")
            deliverVoice(text, target, fresh, chat, null)
        }
    }

    internal fun probeStrip(show: Boolean, variant: String?, animate: Boolean, cycle: Boolean, line: String?) {
        if (!initialized || !interactive() || petView == null) {
            Log.i(TAG, "strip probe: no pet")
            return
        }
        probeCycle?.let(main::removeCallbacks)
        probeCycle = null
        if (cycle) {
            cycleStripProbe(line)
            return
        }
        if (!show) {
            hideOrbStrip(animate = false)
            Log.i(TAG, "strip probe: hidden")
            return
        }
        val seam = when (variant) {
            "edge" -> SeamVariant.EDGE
            "tuck12" -> SeamVariant.TUCK12
            "tuck28" -> SeamVariant.TUCK28
            else -> SeamVariant.TONGUE
        }
        showStripProbe(seam, animate, line)
    }

    private fun showStripProbe(variant: SeamVariant, animate: Boolean, line: String?) {
        val strip = ensureOrbStrip() ?: run {
            Log.i(TAG, "strip probe: no host")
            return
        }
        val place = orbStripPlacement(variant) ?: run {
            Log.i(TAG, "strip probe: no placement")
            return
        }
        probeVariant = variant
        strip.setStopVisible(true)
        strip.setDot(OverlayPalette.of(hostContext ?: app).accent)
        strip.show(place, line ?: AppStrings.get(app, R.string.pet_orb_probe_line), animate && animationsOn())
        Log.i(TAG, "strip probe: variant=$variant dock=${dock.side} ${strip.describe()}")
    }

    private fun cycleStripProbe(line: String?) {
        val variants = listOf(SeamVariant.TONGUE, SeamVariant.EDGE, SeamVariant.TUCK12, SeamVariant.TUCK28)
        var index = 0
        val run = object : Runnable {
            override fun run() {
                if (index >= variants.size) {
                    hideOrbStrip(animate = false)
                    probeCycle = null
                    return
                }
                showStripProbe(variants[index++], animate = false, line)
                main.postDelayed(this, PROBE_STEP_MS)
            }
        }
        probeCycle = run
        main.post(run)
    }

    // ---- 语音问 ---------------------------------------------------------------------------------

    private var recorder: WavRecorder? = null
    private var recorderMic = 0
    private var recording = 0L

    /** Hold to talk: the offline voice model's recorder, as the chat's dictation records for it. */
    private fun micDown() {
        val view = askView ?: return
        machine.onTouch(now())
        val voice = askTarget?.voice
        when {
            voice == AskTarget.VOICE_MISSING -> view.setStatus(AppStrings.get(app, R.string.pet_ask_voice_missing))
            voice != AskTarget.VOICE_READY -> view.setStatus(AppStrings.get(app, R.string.pet_ask_voice_off))
            app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> foregroundVoice(view)
            else -> startRecording(view)
        }
    }

    private fun startRecording(view: AskBarView) {
        stopRecording()
        val gen = ++recording
        lateinit var r: WavRecorder
        r = WavRecorder(
            maxSeconds = 60,
            onLevel = {},
            onLimit = { main.post { if (gen == recording) micUp(cancelled = false) } },
            onError = { main.post {
                if (gen == recording && recorder === r) {
                    stopRecording()
                    if (askView === view) view.setStatus(AppStrings.get(app, R.string.pet_ask_not_heard), error = true)
                }
            } },
        )
        val mic = MicArbiter.beginDictation()
        if (!runCatching { r.start() }.getOrDefault(false)) {
            MicArbiter.endDictation(mic)
            foregroundVoice(view)
            return
        }
        recorder = r
        recorderMic = mic
        view.setState(listening = true)
        view.setStatus(AppStrings.get(app, R.string.pet_ask_listening))
        main.postDelayed({ if (gen == recording && recorder === r) checkSilenced(r, view) }, SILENCE_CHECK_MS)
    }

    /**
     * Android hands a background app silence instead of the microphone (while-in-use, docs/design/
     * pets.md §6.3): the recording then goes to the app, brought to the front with the text so far.
     */
    private fun checkSilenced(r: WavRecorder, view: AskBarView) {
        if (Build.VERSION.SDK_INT < 29) return
        val am = app.getSystemService(AudioManager::class.java) ?: return
        val mine = am.activeRecordingConfigurations.filter { it.clientAudioSessionId == r.sessionId }
        val silenced = mine.any { it.isClientSilenced }
        Log.i(TAG, "pet mic: configs=${mine.size} silenced=$silenced dshService=${DshService.isRunning} petService=${PetOverlayService.running}")
        if (!silenced) return
        foregroundVoice(view)
    }

    private fun foregroundVoice(view: AskBarView) {
        if (askView !== view) return
        stopRecording()
        // Keep the overlay draft until the app accepts the handoff. A vendor
        // may block Activity launch and notifications at the same time.
        removeAsk()
        evaluate()
        pendingVoice = true
        openIntent(EntryActivity.openApp(app), null)
    }

    /** Let go: the recording goes to the voice model, its words into the box. Slid off: dropped. */
    private fun micUp(cancelled: Boolean) {
        val r = recorder ?: return
        val view = askView
        val mic = recorderMic
        recorder = null
        val gen = ++recording
        view?.setState(listening = false)
        if (cancelled) {
            worker.execute {
                runCatching { r.discard() }
                main.post { MicArbiter.endDictation(mic) }
            }
            view?.setStatus(null)
            return
        }
        view?.setStatus(AppStrings.get(app, R.string.pet_ask_transcribing))
        worker.execute {
            val wav = runCatching { r.finish() }.getOrNull()
            main.post {
                MicArbiter.endDictation(mic)
                if (gen != recording || view == null || askView !== view) return@post
                if (wav == null || wav.size < MIN_WAV_BYTES) {
                    view.setStatus(AppStrings.get(app, R.string.pet_ask_not_heard))
                    return@post
                }
                callDart("transcribe", mapOf("wav" to wav)) { raw ->
                    if (gen != recording || askView !== view) return@callDart
                    val r2 = raw as? Map<*, *>
                    val words = (r2?.get("text") as? String)?.trim()
                    if (words.isNullOrEmpty()) {
                        view.setStatus((r2?.get("problem") as? String) ?: AppStrings.get(app, R.string.pet_ask_not_heard), error = r2?.get("problem") != null)
                    } else {
                        view.setStatus(null)
                        view.insert(words)
                    }
                }
            }
        }
    }

    private fun stopRecording() {
        recording++
        val r = recorder ?: return
        val mic = recorderMic
        recorder = null
        askView?.setState(listening = false)
        worker.execute {
            runCatching { r.discard() }
            main.post { MicArbiter.endDictation(mic) }
        }
    }

    // ---- opening the app ------------------------------------------------------------------------

    /**
     * Opens [sessionId] (or just the app) through EntryActivity. A tap on an overlay may start an
     * activity on stock Android, but some vendors block 「后台弹出界面」 on their own: when the app
     * isn't up shortly after, a notification takes the tap instead (as SystemActions.kt does).
     */
    private fun open(sessionId: String?, host: String?, title: String?) = openIntent(
        if (sessionId != null) EntryActivity.openChat(app, sessionId, host) else EntryActivity.openApp(app),
        title,
    )

    private fun openIntent(entry: Intent, title: String?) {
        val token = windows.openToken() ?: return
        val intent = entry.putExtra(EntryActivity.EXTRA_PET_GENERATION, token).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            app.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "open failed", e)
            if (windows.acceptsOpen(token)) postOpenNotice(intent, title)
            return
        }
        main.postDelayed({ if (started == 0 && windows.acceptsOpen(token)) postOpenNotice(intent, title) }, OPEN_CHECK_MS)
    }

    fun acceptsOpen(token: String?) = windows.acceptsOpen(token)

    private fun postOpenNotice(intent: Intent, title: String?) {
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        if (!nm.areNotificationsEnabled()) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL_OPEN, AppStrings.get(app, R.string.pet_open_channel), NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(app, NOTICE_ID, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        openPendingIntent = pi
        val n = Notification.Builder(app, CHANNEL_OPEN)
            .setSmallIcon(R.drawable.ic_stat_dsh)
            .setContentTitle(title ?: AppStrings.APP_NAME)
            .setContentText(AppStrings.get(app, R.string.pet_tap_open))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .build()
        nm.notify(NOTICE_ID, n)
    }

    // ---- system signals -------------------------------------------------------------------------

    private fun interactive(): Boolean {
        val pm = app.getSystemService(PowerManager::class.java)
        val km = app.getSystemService(KeyguardManager::class.java)
        return (pm?.isInteractive != false) && (km?.isKeyguardLocked != true)
    }

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF || !interactive()) capture.invalidate()
            evaluate()
        }
    }

    private val config = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) {
            val diff = lastConfig?.diff(newConfig) ?: 0
            lastConfig = Configuration(newConfig)
            val relevant = ActivityInfo.CONFIG_ORIENTATION or ActivityInfo.CONFIG_SCREEN_SIZE or ActivityInfo.CONFIG_UI_MODE or ActivityInfo.CONFIG_DENSITY or ActivityInfo.CONFIG_FONT_SCALE
            if (diff and relevant == 0) return
            capture.invalidate()
            removeWindows()
            if (diff and ActivityInfo.CONFIG_DENSITY != 0) {
                loadRequest++
                loadingKey = null
                loaded?.release()
                loaded = null
                loadedKey = null
                machine.last?.let { p -> p.packId?.let { ensurePack(it, p.size) } }
            }
            evaluate()
        }

        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = Unit

        override fun onTrimMemory(level: Int) {
            if (petView == null) loaded?.release()
        }
    }

    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            if (activity !is MainActivity) return
            started++
            capture.invalidate()
            machine.onForeground(true, now())
            app.getSystemService(NotificationManager::class.java)?.cancel(NOTICE_ID)
            evaluate()
            deliverSettings()
        }

        override fun onActivityStopped(activity: Activity) {
            if (activity !is MainActivity) return
            started = (started - 1).coerceAtLeast(0)
            if (started == 0) {
                machine.onForeground(false, now())
                if (returnAsk) machine.onTouch(now())
                evaluate()
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) {
            if (activity !is MainActivity || !pendingVoice) return
            pendingVoice = false
            val owner = listener
            val generation = ++voiceHandoff
            callDart("voiceFallback", mapOf("text" to askDraft)) { raw ->
                if (listener !== owner || generation != voiceHandoff) return@callDart
                val text = (raw as? Map<*, *>)?.get("text") as? String ?: return@callDart
                val limit = AskBarView.MAX_TEXT
                val cut = if (text.length > limit && text[limit - 1].isHighSurrogate() && text[limit].isLowSurrogate()) limit - 1 else limit
                askDraft = text.take(cut)
                prefs.edit().putString("ask_draft", askDraft).apply()
                returnAsk = true
                activity.moveTaskToBack(true)
            }
        }
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    /** For the debug probe: 问一句's bar, as the menu opens it. */
    fun probeAsk(fresh: Boolean) {
        if (alive(petTicket)) showAsk(fresh)
    }

    /** For the debug probe: what the window is doing now. */
    fun debugState(): Map<String, Any?> = mapOf(
        "visibility" to lastVisibility.name.lowercase(),
        "why" to lastWhy,
        "host" to host.name.lowercase(),
        "x" to petParams?.x, "y" to petParams?.y, "w" to petParams?.width, "h" to petParams?.height,
        "frame" to petView?.frame?.let { "${it.left},${it.top} ${it.width}x${it.height}" },
        "hit" to loaded?.frames?.hit(petView?.shown)?.let { "${it.left},${it.top} ${it.width}x${it.height}" },
        "shown" to petView?.shown?.name,
        "dragging" to dragging,
        "dock" to "${dock.side.name.lowercase()}@${"%.2f".format(dock.yFraction)}",
        "dimmed" to dimmed,
        "alpha" to petView?.alpha,
        "looping" to looping,
        "bubble" to bubbleId,
        "bubbleAsk" to machine.bubbles.current(now())?.ask?.let { "${it.kind}:${it.approvable}:${it.choices.size}" },
        "menu" to (menuView != null),
        "ask" to askView?.let { askParams?.let { p -> "${p.x},${p.y} ${p.width}w" } ?: "open" },
        "askTarget" to askTarget?.let { it.chat(askFresh || it.current == null)?.line },
        "answering" to answerState.bubbleId,
        "service" to PetOverlayService.running,
        "scale" to loaded?.screenScale,
        "pack" to loadedKey,
        "speaksForResults" to speaksForResults,
        "windowGeneration" to windows.generation,
        "registeredWindows" to windows.registeredWindows,
        "pendingRemovals" to windows.pendingRemovals,
        "lastRemovalFailure" to windows.lastRemovalFailure,
    )
}
