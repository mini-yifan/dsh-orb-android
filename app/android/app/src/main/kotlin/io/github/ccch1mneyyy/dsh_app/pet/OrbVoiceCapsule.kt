package io.github.ccch1mneyyy.dsh_app.pet

import android.content.Context
import android.view.View
import android.view.WindowManager
import kotlin.math.roundToInt

/**
 * The recording capsule's single window, placed just above the pet's body (below it when there is
 * no room above). It never takes touch: the finger stays on the pet, which is what tracks the
 * slide-up cancel.
 */
internal class OrbVoiceCapsule(
    private val ctx: Context,
    private val windows: OverlayWindowOwner,
    private val params: (w: Int, h: Int, flags: Int) -> WindowManager.LayoutParams,
    private val add: (OverlayWindowOwner.Ticket, View, WindowManager.LayoutParams) -> Boolean,
    private val update: (View, WindowManager.LayoutParams) -> Unit,
    private val remove: (View) -> Unit,
) {
    private var view: OrbVoiceView? = null
    private var p: WindowManager.LayoutParams? = null

    val visible: Boolean get() = view != null

    fun show(body: ScreenRect, area: SafeArea, animate: Boolean) {
        if (view != null) return
        val ticket = windows.claim(OverlayWindowOwner.Role.VOICE) ?: return
        val margin = ctx.dp(OrbStripGeometry.MARGIN_DP)
        val gap = ctx.dp(8f)
        val w = (220f * ctx.resources.displayMetrics.density).roundToInt()
            .coerceAtMost(area.screenW - area.left - area.right - margin * 2)
        val h = (82f * ctx.resources.displayMetrics.density).roundToInt()
        val x = (body.centerX - w / 2)
            .coerceIn(area.left + margin, maxOf(area.left + margin, area.screenW - area.right - margin - w))
        val above = body.top - h - gap
        val below = body.bottom + gap
        val y = if (above >= area.top + margin) {
            above
        } else {
            minOf(below, maxOf(area.top + margin, area.screenH - area.bottom - margin - h))
        }
        val v = OrbVoiceView(ctx)
        val wp = params(w, h, WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE).apply {
            this.x = x
            this.y = y
        }
        if (!add(ticket, v, wp)) return
        view = v
        p = wp
        if (animate) {
            v.alpha = 0f
            v.animate().alpha(1f).setDuration(120).start()
        }
    }

    fun setLevel(level: Double) {
        view?.setLevel(level)
    }

    fun setElapsed(ms: Long) {
        view?.setElapsed(ms)
    }

    fun setHint(text: String, danger: Boolean = false) {
        view?.setHint(text, danger)
    }

    fun hide() {
        view?.let(remove)
        view = null
        p = null
    }
}