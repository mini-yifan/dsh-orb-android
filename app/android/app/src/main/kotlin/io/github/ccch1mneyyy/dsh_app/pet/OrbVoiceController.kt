package io.github.ccch1mneyyy.dsh_app.pet

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.ccch1mneyyy.dsh_app.nativemedia.MicArbiter
import io.github.ccch1mneyyy.dsh_app.runtime.DshService
import io.github.ccch1mneyyy.dsh_app.voice.WavRecorder

/**
 * The orb's hold-to-talk capture: one [WavRecorder], the mic arbiter, and the "did Android hand
 * us silence" check, with no view. The overlay owns the capsule and decides what to do with the
 * WAV; this class only runs the capture and logs what the E2/E3 experiments measure.
 */
internal class OrbVoiceController(
    private val context: Context,
    private val main: Handler = Handler(Looper.getMainLooper()),
) {
    interface Listener {
        fun onLevel(level: Double)
        fun onLimit()
        fun onError()
        fun onSilenced()
    }

    private class Held(val recorder: WavRecorder, val startedAt: Long, val silenced: Boolean, val mic: Int)

    private var recorder: WavRecorder? = null
    private var mic = 0
    private var generation = 0
    private var startedAt = 0L
    private var silenced = false

    val recording: Boolean get() = recorder != null
    val elapsedMs: Long get() = if (startedAt == 0L) 0 else System.currentTimeMillis() - startedAt

    /** Opens the mic; false when it cannot be opened (permission is checked by the caller). */
    fun start(listener: Listener): Boolean {
        abort()
        val gen = ++generation
        lateinit var r: WavRecorder
        r = WavRecorder(
            maxSeconds = MAX_SECONDS,
            onLevel = { level -> main.post { if (gen == generation) listener.onLevel(level) } },
            onLimit = { main.post { if (gen == generation) listener.onLimit() } },
            onError = {
                main.post {
                    if (gen == generation && recorder === r) {
                        abort()
                        listener.onError()
                    }
                }
            },
        )
        val arbiter = MicArbiter.beginDictation()
        if (!runCatching { r.start() }.getOrDefault(false)) {
            MicArbiter.endDictation(arbiter)
            Log.i(TAG, "orb voice start failed")
            return false
        }
        recorder = r
        mic = arbiter
        startedAt = System.currentTimeMillis()
        silenced = false
        main.postDelayed({ if (gen == generation && recorder === r) checkSilenced(r, listener) }, SILENCE_CHECK_MS)
        Log.i(TAG, "orb voice start")
        return true
    }

    /** Release: hands the WAV to [done] (null when the capture failed). */
    fun finish(done: (ByteArray?) -> Unit) {
        val held = detach() ?: return done(null)
        Thread({
            val wav = runCatching { held.recorder.finish() }.getOrNull()
            main.post {
                MicArbiter.endDictation(held.mic)
                Log.i(
                    TAG,
                    "orb voice finish elapsed=${System.currentTimeMillis() - held.startedAt}ms " +
                        "bytes=${wav?.size ?: 0} silenced=${held.silenced}",
                )
                done(wav)
            }
        }, "dsh-orb-voice-finish").start()
    }

    /** Slide-up cancel / teardown: drops the capture. */
    fun cancel() {
        val held = detach() ?: return
        Thread({
            runCatching { held.recorder.discard() }
            main.post {
                MicArbiter.endDictation(held.mic)
                Log.i(TAG, "orb voice cancel")
            }
        }, "dsh-orb-voice-discard").start()
    }

    /** The arbiter token of the capture being finished/cancelled, carried by [Held]. */
    private fun detach(): Held? {
        val r = recorder ?: return null
        val at = startedAt
        val silent = silenced
        val arbiter = mic
        recorder = null
        mic = 0
        startedAt = 0
        generation++
        return Held(r, at, silent, arbiter)
    }

    private fun abort() {
        val r = recorder ?: return
        val arbiter = mic
        recorder = null
        mic = 0
        startedAt = 0
        generation++
        Thread({
            runCatching { r.discard() }
            main.post { MicArbiter.endDictation(arbiter) }
        }, "dsh-orb-voice-abort").start()
    }

    private fun checkSilenced(r: WavRecorder, listener: Listener) {
        if (Build.VERSION.SDK_INT < 29) return
        val am = context.getSystemService(AudioManager::class.java) ?: return
        val mine = am.activeRecordingConfigurations.filter { it.clientAudioSessionId == r.sessionId }
        val silent = mine.any { it.isClientSilenced }
        Log.i(TAG, "orb voice mic: configs=${mine.size} silenced=$silent dshService=${DshService.isRunning}")
        if (silent) {
            silenced = true
            listener.onSilenced()
        }
    }

    companion object {
        const val TAG = "DshPet"
        const val MIN_WAV_BYTES = 44 + 9_600
        private const val MAX_SECONDS = 60
        private const val SILENCE_CHECK_MS = 400L
    }
}