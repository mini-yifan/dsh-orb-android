package io.github.ccch1mneyyy.dsh_app.pet

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator

/**
 * The strip's two windows (strip + optional tongue) and its emergence/retraction animation.
 * The strip is anchored on the pet's side and grows away from the dock: its near edge stays put,
 * the far edge travels. The tongue is a non-touchable neck above the pet, so touches on the pet
 * still reach the pet.
 *
 * The caller owns the host, palette and window lifecycle; this class only draws and animates.
 * Window roles are released asynchronously (platform detachment), so a variant change hides,
 * waits for the role to come back, and retries instead of dropping the frame.
 */
internal class OrbStrip(
    private val ctx: Context,
    private val windows: OverlayWindowOwner,
    private val params: (w: Int, h: Int, flags: Int, behind: Boolean) -> WindowManager.LayoutParams,
    private val add: (OverlayWindowOwner.Ticket, View, WindowManager.LayoutParams) -> Boolean,
    private val update: (View, WindowManager.LayoutParams) -> Unit,
    private val remove: (View) -> Unit,
    private val onStop: () -> Unit,
) {
    private val palette = OverlayPalette.of(ctx)
    private val main = Handler(Looper.getMainLooper())
    private var view: OrbStripView? = null
    private var stripParams: WindowManager.LayoutParams? = null
    private var tongue: View? = null
    private var tongueParams: WindowManager.LayoutParams? = null
    private var placement: StripPlacement? = null
    private var anim: ValueAnimator? = null
    /** Invalidates posted retries when the caller hides or shows something else. */
    private var token = 0

    val visible: Boolean get() = view != null
    val current: StripPlacement? get() = placement

    fun show(next: StripPlacement, line: String, animate: Boolean, attempt: Int = 0) {
        if (attempt == 0) token++
        val mine = token
        val existing = view
        val p = stripParams
        val old = placement
        if (existing != null && p != null && old != null) {
            if (old.side != next.side || old.behind != next.behind ||
                old.strip.width != next.strip.width || old.strip.height != next.strip.height
            ) {
                // The window type/role has to change: hide, let the role come back, then show.
                hide(animate = false)
                val after = token
                main.postDelayed({ if (token == after) show(next, line, animate, attempt + 1) }, REBUILD_MS)
                return
            }
            placement = next
            existing.setSide(next.side, next.nearInset)
            existing.setLine(line)
            p.y = next.strip.top
            update(existing, p)
            updateTongue(next)
            return
        }
        placement = next
        val ticket = windows.claim(OverlayWindowOwner.Role.STRIP)
        if (ticket == null) {
            retry(mine, next, line, animate, attempt)
            return
        }
        val v = OrbStripView(ctx, onStop).apply {
            setSide(next.side, next.nearInset)
            setLine(line)
        }
        val fullW = next.strip.width
        val startW = next.startWidth.coerceAtMost(fullW)
        val wp = params(fullW, next.strip.height, 0, next.behind).apply {
            width = startW
            x = if (next.side == Side.RIGHT) next.strip.right - startW else next.strip.left
            y = next.strip.top
        }
        showTongue(next)
        if (!add(ticket, v, wp)) {
            removeTongue()
            retry(mine, next, line, animate, attempt)
            return
        }
        view = v
        stripParams = wp
        Log.i(TAG, "strip add side=${next.side} behind=${next.behind} start=$startW full=$fullW tongue=${next.tongue}")
        if (animate && fullW > startW) {
            v.alpha = 0.55f
            animateWidth(next, startW, fullW) { v.alpha = 1f }
        } else {
            wp.width = fullW
            wp.x = next.strip.left
            update(v, wp)
        }
    }

    fun hide(animate: Boolean) {
        token++
        val v = view
        val p = stripParams
        val next = placement
        anim?.cancel()
        anim = null
        if (v == null || p == null || next == null) {
            finishHide()
            return
        }
        if (!animate) {
            finishHide()
            return
        }
        val from = p.width
        if (from <= next.startWidth) {
            finishHide()
            return
        }
        animateWidth(next, from, next.startWidth) { finishHide() }
    }

    /** The input bar owns the strip's window while it types; only the seam shows. */
    fun showSeam(place: StripPlacement) {
        showTongue(place)
    }

    fun hideSeam() {
        removeTongue()
    }

    fun setLine(line: String) {
        view?.setLine(line)
    }

    fun setStopVisible(on: Boolean) {
        view?.setStopVisible(on)
    }

    fun setDot(color: Int) {
        view?.setDot(color)
    }

    /** Debug/probe readout. */
    fun describe(): String {
        val next = placement ?: return "strip=none"
        return "strip=${next.strip.left},${next.strip.top},${next.strip.width}x${next.strip.height} " +
            "tongue=${next.tongue} behind=${next.behind} nearInset=${next.nearInset}"
    }

    private fun retry(mine: Int, next: StripPlacement, line: String, animate: Boolean, attempt: Int) {
        if (attempt >= MAX_ATTEMPTS) {
            Log.w(TAG, "strip: no window after $attempt attempts")
            return
        }
        main.postDelayed({ if (token == mine) show(next, line, animate, attempt + 1) }, RETRY_MS)
    }

    private fun animateWidth(next: StripPlacement, from: Int, to: Int, done: () -> Unit) {
        val v = view ?: return done()
        val p = stripParams ?: return done()
        val animator = ValueAnimator.ofInt(from, to).apply {
            duration = if (to > from) EXPAND_MS else COLLAPSE_MS
            interpolator = EASE
            addUpdateListener { a ->
                val w = a.animatedValue as Int
                p.width = w
                p.x = if (next.side == Side.RIGHT) next.strip.right - w else next.strip.left
                update(v, p)
            }
        }
        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(animation: android.animation.Animator) {
                cancelled = true
            }

            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (anim === animation) anim = null
                if (!cancelled) done()
            }
        })
        anim = animator
        animator.start()
    }

    private fun showTongue(next: StripPlacement) {
        val r = next.tongue ?: return
        val ticket = windows.claim(OverlayWindowOwner.Role.TONGUE) ?: return
        val v = View(ctx).apply {
            background = GradientDrawable().apply {
                cornerRadius = r.height / 2f
                setColor(palette.fill)
                setStroke(ctx.dp(1f).coerceAtLeast(1), palette.stroke)
            }
        }
        val p = params(r.width, r.height, WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, false).apply {
            x = r.left
            y = r.top
        }
        if (!add(ticket, v, p)) return
        tongue = v
        tongueParams = p
    }

    private fun updateTongue(next: StripPlacement) {
        val v = tongue
        val p = tongueParams
        val r = next.tongue
        if (v == null || p == null) {
            if (r != null) showTongue(next)
            return
        }
        if (r == null) {
            removeTongue()
            return
        }
        p.x = r.left
        p.y = r.top
        p.width = r.width
        p.height = r.height
        update(v, p)
    }

    private fun removeTongue() {
        tongue?.let(remove)
        tongue = null
        tongueParams = null
    }

    private fun finishHide() {
        anim?.cancel()
        anim = null
        view?.let(remove)
        view = null
        stripParams = null
        removeTongue()
        placement = null
    }

    private companion object {
        const val TAG = "DshPet"
        const val EXPAND_MS = 260L
        const val COLLAPSE_MS = 200L
        const val REBUILD_MS = 80L
        const val RETRY_MS = 120L
        const val MAX_ATTEMPTS = 4
        val EASE = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
    }
}