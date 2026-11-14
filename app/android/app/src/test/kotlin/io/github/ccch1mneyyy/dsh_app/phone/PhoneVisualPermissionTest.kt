package io.github.ccch1mneyyy.dsh_app.phone

import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

/**
 * A tap_xy is located purely by pixel and is confirmed exactly like any other write: by
 * ConfirmMode, the per-app first question, the every-write list and the hard rules. There is no
 * separate coordinate permission. These tests cover Core + real ledger, Tasks and SafetyGate;
 * no Android screenshot or device is simulated as real.
 */
class PhoneVisualPermissionTest {
    private class Fixture : AutoCloseable {
        val tasks = PhoneTasks()
        val ledger = PhoneTaskObservations(tasks)
        var scope: VisualScope? = VisualScope.Background("display-a", PhoneAppIdentity("example.app", 10123), Any())
        var knownTarget = true
        var accepted = false
        var nextId = 0
        var id = "b0"
        var image: ByteArray? = byteArrayOf(1)
        var textCoordinates = false
        var coordinateCandidate = false
        var afterText: String? = null
        var afterImage: ByteArray? = null
        var afterCoordinates = false
        var imageRequest: Pair<Boolean, Boolean>? = null
        var screenText = "Details"
        var policy = PhonePolicies(confirmMode = ConfirmMode.ASK)
        var decision: (ConfirmRequest) -> Decision = { Decision.ALLOW_APP_FOR_TASK }
        val asks = mutableListOf<ConfirmRequest>()
        var actions = 0
        var onAsk: () -> Unit = {}
        val target get() = TargetInfo(id, 1, "button", "Details", "example.app", "Example", false, false, false)
        private val physical = object : PhoneDriver {
            override val screen = PhoneScreen.BACKGROUND
            override fun availability() = Availability(true)
            override fun observe(detail: Boolean, image: Boolean): ScreenSnapshot {
                id = "b${++nextId}"; accepted = false
                coordinateCandidate = this@Fixture.image != null || textCoordinates
                return ScreenSnapshot(id, screenText, "Example", "example.app", image = this@Fixture.image)
            }
            override fun observe(detail: Boolean, image: Boolean, automaticImage: Boolean): ScreenSnapshot {
                imageRequest = image to automaticImage
                return observe(detail, image)
            }
            override fun visualScope(screenId: String) = scope.takeIf { screenId == id }
            override fun acceptVisualObservation(screenId: String) { if (screenId == id && coordinateCandidate) accepted = true }
            override fun coordinateTarget(action: PhoneAction.TapXY) = scope?.takeIf { accepted && knownTarget && action.screenId == id }
                ?.let { VisualTarget(it, target) }
            override fun target(screenId: String, elementId: Int): TargetInfo? = null
            override fun perform(action: PhoneAction): ActionResult {
                if (!accepted) return ActionResult(false, "no image")
                accepted = false; actions++
                val text = afterText ?: return ActionResult(true, "test action")
                id = "b${++nextId}"
                coordinateCandidate = afterCoordinates || afterImage != null
                return ActionResult(true, "test action", ScreenSnapshot(id, text, "Example", "example.app", image = afterImage))
            }
            override fun foregroundPackage() = "example.app"
            override fun collect(screenId: String, listElementId: Int, maxPages: Int, until: String?) = error("unused")
            override fun open(target: PhoneAppIdentity) = error("unused")
        }
        val core = PhoneCore(object : PhoneEnv {
            override fun enabled() = true
            override fun policies() = policy
            override fun isLocked() = false
            override fun gate() = object : UserGate {
                override fun confirm(request: ConfirmRequest): Decision {
                    asks += request; onAsk(); return decision(request)
                }
                override fun handoff(reason: String, timeoutMs: Long): HandoffResult = error("unused")
                override fun status(text: String?) {}
            }
            override fun driver(screen: PhoneScreen) = physical
            override fun driver(screen: PhoneScreen, owner: PhoneTasks.Owner) = ledger.bind(physical, owner)
            override fun a11yState() = A11yState.CONNECTED
            override fun backgroundUnavailable() = "unavailable"
            override fun system(): SystemApi = error("unused")
            override fun zone() = ZoneId.of("UTC")
            override fun notifyFix(kind: String) {}
            override fun sleep(ms: Long) {}
        }, tasks, ActionLog())
        fun read(image: Boolean = true) = core.call(PhoneToolCatalog.SCREEN, mapOf("background" to true, "image" to image))
        fun tap(gesture: String = "tap") = core.call(PhoneToolCatalog.TAP_XY,
            mapOf("observation" to id, "x" to 30, "y" to 40, "action" to gesture))
        override fun close() { core.worker.shutdown() }
    }

    /** The first coordinate tap in a task asks the ordinary first-in-app question; approving
     * that app lets every later coordinate tap in the same task run without another card. */
    @Test fun askModeAsksOncePerAppThenRunsSilently() {
        Fixture().use { f ->
            f.read(); assertFalse(f.tap().isError)
            assertEquals(1, f.asks.size)
            assertTrue(f.asks[0].firstInApp); assertFalse(f.asks[0].offerTaskWaiver)
            assertTrue(f.tasks.isApproved("example.app"))
            f.read(); assertFalse(f.tap().isError)
            f.read(); assertFalse(f.tap("long").isError) // long is a write like any other
            assertEquals(1, f.asks.size); assertEquals(3, f.actions)
        }
    }

    /** 始终免确认 means exactly that for coordinate taps too: no card is ever shown. */
    @Test fun alwaysModeNeverAsksForCoordinates() {
        Fixture().use { f ->
            f.policy = PhonePolicies(confirmMode = ConfirmMode.ALWAYS)
            repeat(2) { f.read(); assertFalse(f.tap().isError) }
            assertEquals(0, f.asks.size); assertEquals(2, f.actions)
            assertFalse(f.tasks.isApproved("example.app")); assertFalse(f.tasks.isWaived)
        }
    }

    /** 本任务免确认 offers the waiver on the first question; taking it hides the rest. */
    @Test fun taskModeOffersWaiverOnTheFirstQuestion() {
        Fixture().use { f ->
            f.policy = PhonePolicies(confirmMode = ConfirmMode.TASK)
            f.decision = { Decision.ALLOW_ALL_FOR_TASK }
            f.read(); assertTrue(f.asks.isEmpty()) // no question until a write
            assertFalse(f.tap().isError)
            assertEquals(1, f.asks.size); assertTrue(f.asks[0].offerTaskWaiver)
            f.read(); assertFalse(f.tap().isError)
            assertEquals(1, f.asks.size); assertTrue(f.tasks.isWaived)
        }
    }

    /** The every-write list still asks on every coordinate tap in ASK mode. A fully waived task
     * (ALWAYS) skips the list, exactly as it does for an element tap. */
    @Test fun everyWriteListStillAsksForEveryCoordinates() {
        Fixture().use { f ->
            f.policy = PhonePolicies(confirmMode = ConfirmMode.ASK, confirmEveryWritePackages = listOf("example.app"))
            f.decision = { Decision.ALLOW_ONCE }
            repeat(2) { f.read(); assertFalse(f.tap().isError) }
            assertEquals(2, f.asks.size); assertTrue(f.asks.all { !it.firstInApp && !it.offerTaskWaiver })
        }
    }

    /** A one-shot answer does not approve the app, so the next coordinate tap asks again. */
    @Test fun allowOnceNeverPersists() {
        Fixture().use { f ->
            f.decision = { Decision.ALLOW_ONCE }
            repeat(2) { f.read(); assertFalse(f.tap().isError) }
            assertEquals(2, f.asks.size); assertFalse(f.tasks.isApproved("example.app"))
        }
    }

    /** Hard read refusals still stop a coordinate tap with no card. */
    @Test fun deniedAppRefusesCoordinatesWithoutAsking() {
        Fixture().use { f ->
            f.policy = PhonePolicies(denyPackages = listOf("example.app"))
            f.read()
            assertTrue(f.tap().isError); assertEquals(0, f.actions); assertEquals(0, f.asks.size)
        }
    }

    /** Text-only (OCR) observations still follow the same, single confirmation path. */
    @Test fun textOnlyObservationsFollowTheSameConfirmationPath() {
        Fixture().use { f ->
            f.image = null; f.textCoordinates = true; f.knownTarget = false
            f.screenText = "V1 \"Details\" box=[20,30,50,60) point=(30,40)"
            f.policy = PhonePolicies(confirmMode = ConfirmMode.ALWAYS)
            repeat(2) {
                assertFalse(f.read(image = false).isError)
                assertTrue(f.accepted)
                assertFalse(f.tap().isError)
            }
            assertEquals(2, f.actions); assertEquals(0, f.asks.size)
        }
    }

    @Test fun imagePresenceModesReachTheDriverWithoutSkippingTheTaskObservationLedger() {
        val cases = listOf(
            emptyMap<String, Any?>() to (false to true),
            mapOf<String, Any?>("image" to false) to (false to false),
            mapOf<String, Any?>("image" to true) to (true to false),
            mapOf<String, Any?>("image" to null) to (false to false),
            mapOf<String, Any?>("image" to "true") to (false to false),
            mapOf<String, Any?>("image" to "yes") to (false to false),
            mapOf<String, Any?>("image" to 1) to (false to false),
        )
        for ((args, expected) in cases) Fixture().use { f ->
            f.image = null; f.textCoordinates = true
            val result = f.core.call(PhoneToolCatalog.SCREEN, mapOf("background" to true) + args)
            assertFalse(result.isError)
            assertEquals(expected, f.imageRequest)
            // accept reaches the physical driver only when the wrapper remembered this owner/id.
            assertTrue(f.accepted)
        }
    }

    @Test fun textOnlyScreenWithoutDriverCandidateCannotAuthorizeCoordinates() {
        Fixture().use { f ->
            f.image = null; f.knownTarget = false; f.decision = { Decision.ALLOW_ONCE }
            assertFalse(f.read(image = false).isError)
            assertFalse(f.accepted)
            assertTrue(f.tap().isError); assertEquals(0, f.actions)
        }
    }

    @Test fun finalReadRefusalDoesNotAcceptEvenAnAvailableTextCandidate() {
        Fixture().use { f ->
            f.image = null; f.textCoordinates = true
            f.policy = PhonePolicies(denyPackages = listOf("example.app"))
            assertTrue(f.read(image = false).isError)
            assertFalse(f.accepted); assertEquals(0, f.actions)
        }
    }

    /** A post-action observation is a new image and goes through the same single path. */
    @Test fun returnedTextCandidateIsAcceptedOnlyAfterTheAction() {
        Fixture().use { f ->
            f.image = null; f.textCoordinates = true; f.knownTarget = false
            f.afterText = "V1 \"Next\" box=[20,30,50,60) point=(30,40)"; f.afterCoordinates = true
            f.policy = PhonePolicies(confirmMode = ConfirmMode.ALWAYS)
            f.read(image = false)
            val before = f.id
            assertFalse(f.tap().isError)
            assertNotEquals(before, f.id); assertTrue(f.accepted)
            assertFalse(f.tap().isError)
            assertEquals(2, f.actions); assertEquals(0, f.asks.size)
        }
    }

    @Test fun filteredPostActionImageKeepsTheActionFactButDoesNotAcceptItsCandidate() {
        Fixture().use { f ->
            f.knownTarget = false; f.decision = { Decision.ALLOW_ONCE }
            f.afterText = "«银行卡 ****1234»"; f.afterImage = byteArrayOf(1); f.afterCoordinates = true
            f.read()
            val result = f.tap()
            assertFalse(result.isError); assertEquals(1, f.actions)
            assertNull(result.image); assertFalse(f.accepted)
            assertTrue(f.tap().isError); assertEquals(1, f.actions)
        }
    }

    @Test fun filteredImageAndLateOwnerCannotAuthorizeAnAction() {
        Fixture().use { f ->
            f.screenText = "«银行卡 ****1234»"; f.read()
            assertFalse(f.accepted); f.decision = { Decision.ALLOW_ONCE }
            assertTrue(f.tap().isError); assertEquals(0, f.actions)
        }
        Fixture().use { f ->
            f.policy = PhonePolicies(confirmMode = ConfirmMode.ALWAYS); f.read()
            f.onAsk = { f.tasks.stop("stop"); f.tasks.clearStop(); f.tasks.begin() }
            assertFalse(f.tap().isError); assertEquals(1, f.actions) // ALWAYS never asks, so onAsk is unused
        }
    }

    /** A policy tightened while a card is up is re-read before the gesture is admitted. */
    @Test fun policyChangedDuringConfirmationIsReRead() {
        Fixture().use { f ->
            f.decision = { Decision.ALLOW_ONCE }
            f.onAsk = {
                if (f.asks.size == 1) f.policy = PhonePolicies(confirmMode = ConfirmMode.ALWAYS,
                    confirmEveryWritePackages = listOf("example.app"))
            }
            f.read(); assertFalse(f.tap().isError)
            assertEquals(1, f.asks.size)
            assertEquals(1, f.actions)
        }
    }
}
