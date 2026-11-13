package io.github.ccch1mneyyy.dsh_app.phone

import java.time.ZoneId
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** The `screenshot` tool: one clean frame saved + clipboard, and every read-policy refusal. */
class PhoneScreenshotToolTest {
    private val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())
    private var captured: ScreenSnapshot = ScreenSnapshot("s1", "", "Example", "example.app",
        image = jpeg, imageMime = "image/jpeg", readPackages = setOf("example.app"))
    private var policies = PhonePolicies()
    private var savedBytes: ByteArray? = null
    private var locked = false
    private var lastBackground: Boolean? = null

    private val driver = object : PhoneDriver {
        override val screen = PhoneScreen.MAIN
        override fun availability() = Availability(true)
        override fun foregroundPackage() = "example.app"
        override fun observe(detail: Boolean, image: Boolean) = captured
        override fun captureImage() = captured
        override fun collect(screenId: String, listElementId: Int, maxPages: Int, until: String?): CollectResult =
            CollectResult(emptyList(), 0, "done", null)
        override fun target(screenId: String, elementId: Int): TargetInfo? = null
        override fun perform(action: PhoneAction): ActionResult = ActionResult(true, "done")
        override fun open(target: PhoneAppIdentity): ActionResult = ActionResult(true, "done")
    }
    private val core = PhoneCore(object : PhoneEnv {
        override fun enabled() = true
        override fun policies() = policies
        override fun isLocked() = locked
        override fun a11yState() = A11yState.CONNECTED
        override fun backgroundUnavailable() = "unavailable"
        override fun zone() = ZoneId.of("Asia/Shanghai")
        override fun notifyFix(kind: String) {}
        override fun sleep(ms: Long) {}
        override fun driver(screen: PhoneScreen): PhoneDriver? {
            lastBackground = screen == PhoneScreen.BACKGROUND
            return if (screen == PhoneScreen.MAIN) driver else null
        }
        override fun gate() = object : UserGate {
            override fun confirm(request: ConfirmRequest) = Decision.ALLOW_ONCE
            override fun handoff(reason: String, timeoutMs: Long): HandoffResult = HandoffResult.CONTINUE
            override fun status(text: String?) {}
        }
        override fun system() = object : SystemApi {
            override fun apps() = emptyList<AppEntry>()
            override fun isInstalled(pkg: String) = false
            override fun prepareLaunch(app: AppEntry): Either<String, SystemLaunch> = Either.Right(TestSystemLaunch(SystemTarget.Known(app)))
            override fun prepareUrl(url: String): Either<String, SystemLaunch> = error("unexpected URL")
            override fun prepareIntent(spec: IntentSpec, selectedApp: AppEntry?): Either<String, SystemLaunch> =
                Either.Right(TestSystemLaunch(SystemTarget.UserChoice, "intent", spec))
            override fun launch(plan: SystemLaunch, dispatch: PhoneTasks.SystemDispatch) = SysResult(true, "attempted")
            override fun notify(title: String, text: String) = SysResult(true, "ok")
            override fun clipboardSet(text: String) = SysResult(true, "ok")
            override fun clipboardGet() = SysResult(true, "")
            override fun saveScreenshot(jpeg: ByteArray): ScreenshotSaved {
                savedBytes = jpeg
                return ScreenshotSaved("Download/DSH-Go/Screenshot x.jpg", true)
            }
        }
    }, PhoneTasks(), ActionLog())

    @After fun close() = core.worker.shutdown()

    private fun call(vararg args: Pair<String, Any?>) = core.call(PhoneToolCatalog.SCREENSHOT, args.toMap())

    @Test fun savesCleanFrameAndCopiesToClipboard() {
        val out = call()
        assertFalse(out.isError)
        assertArrayEquals(jpeg, savedBytes)
        assertTrue(out.text.contains("Download/DSH-Go"))
        assertTrue(out.text.contains("剪贴板"))
        assertEquals(false, lastBackground)
    }

    @Test fun securePageIsRefusedWithoutSaving() {
        captured = captured.copy(secure = true)
        val out = call()
        assertTrue(out.isError)
        assertNull(savedBytes)
        assertTrue(out.text.contains("没有可保存的截图"))
    }

    @Test fun missingImageIsRefusedWithoutSaving() {
        captured = captured.copy(image = null, imageMime = null)
        val out = call()
        assertTrue(out.isError)
        assertNull(savedBytes)
    }

    @Test fun denyListedImageSourceDropsTheFrame() {
        captured = captured.copy(imageReadPackages = setOf("bank.app"))
        policies = PhonePolicies(denyPackages = listOf("bank.app"))
        val out = call()
        assertTrue(out.isError)
        assertNull(savedBytes)
    }

    @Test fun cardNumberInTextDropsTheFrame() {
        captured = captured.copy(text = "«银行卡 ****1234»")
        val out = call()
        assertTrue(out.isError)
        assertNull(savedBytes)
    }

    @Test fun systemSettingsPageIsRefusedEvenThoughScreenMayReadIt() {
        captured = ScreenSnapshot("s1", "", "Settings", "com.android.settings",
            image = jpeg, imageMime = "image/jpeg", readPackages = setOf("com.android.settings"))
        val out = call()
        assertTrue(out.isError)
        assertNull(savedBytes)
        assertTrue(out.text.contains("只看不动"))
    }

    @Test fun systemSettingsPageIsAllowedWhenThePersonLiftedThatRule() {
        captured = ScreenSnapshot("s1", "", "Settings", "com.android.settings",
            image = jpeg, imageMime = "image/jpeg", readPackages = setOf("com.android.settings"))
        policies = PhonePolicies(unlocks = PhoneUnlocks(systemPages = true))
        val out = call()
        assertFalse(out.isError)
        assertArrayEquals(jpeg, savedBytes)
    }

    @Test fun lockedPhoneIsRefused() {
        locked = true
        val out = call()
        assertTrue(out.isError)
        assertNull(savedBytes)
    }

    @Test fun backgroundArgumentRoutesToBackgroundScreen() {
        // BACKGROUND has no driver in this fake, so it stops at availability; the routing is
        // what matters, and no frame is written in that case either.
        val out = call("background" to true)
        assertTrue(out.isError)
        assertEquals(true, lastBackground)
        assertNull(savedBytes)
    }
}
