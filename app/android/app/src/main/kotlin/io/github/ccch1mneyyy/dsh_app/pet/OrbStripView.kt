package io.github.ccch1mneyyy.dsh_app.pet

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import io.github.ccch1mneyyy.dsh_app.R
import io.github.ccch1mneyyy.dsh_app.i18n.AppStrings

/**
 * The orb strip's surface: one 48 dp capsule, a state dot and one line on the pet's side, and a
 * stop key at the far end. The content order mirrors with the dock side: right-docked → the dot
 * and text sit at the right, the stop at the left; left-docked → the other way round. The view is
 * laid out at the window's current width, so the emergence animation reveals it from the pet side
 * instead of re-flowing a full-width page.
 */
internal class OrbStripView(
    context: Context,
    private val onStop: () -> Unit,
) : LinearLayout(context) {
    private val palette = OverlayPalette.of(context)
    private val line = TextView(context).apply {
        textSize = 13f
        setTextColor(palette.ink)
        maxLines = 1
        includeFontPadding = false
    }
    private val dot = View(context)
    private val status = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val stop = StopKey(context, palette, onStop)
    private var side = Side.RIGHT
    private var nearInset = -1

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = context.dp(OrbStripGeometry.HEIGHT_DP / 2f).toFloat()
            setColor(palette.fill)
            setStroke(context.dp(1f).coerceAtLeast(1), palette.stroke)
        }
        setDot(palette.accent)
        setSide(Side.RIGHT, context.dp(OrbStripGeometry.NEAR_INSET_DP))
    }

    fun setSide(side: Side, nearInset: Int) {
        if (this.side == side && this.nearInset == nearInset && childCount > 0) return
        this.side = side
        this.nearInset = nearInset
        removeAllViews()
        status.removeAllViews()
        val dotParams = LayoutParams(context.dp(8f), context.dp(8f))
        val lineParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        if (side == Side.RIGHT) {
            // Near edge is the right: stop on the left, dot/text against the pet.
            setPadding(context.dp(8f), 0, nearInset, 0)
            addView(stop, LayoutParams(context.dp(48f), LayoutParams.MATCH_PARENT))
            status.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            line.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            line.ellipsize = TextUtils.TruncateAt.START
            status.addView(line, lineParams)
            dotParams.marginStart = context.dp(6f)
            status.addView(dot, dotParams)
            addView(status, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        } else {
            setPadding(nearInset, 0, context.dp(8f), 0)
            status.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            line.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            line.ellipsize = TextUtils.TruncateAt.END
            dotParams.marginEnd = context.dp(6f)
            status.addView(dot, dotParams)
            status.addView(line, lineParams)
            addView(status, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            addView(stop, LayoutParams(context.dp(48f), LayoutParams.MATCH_PARENT))
        }
    }

    fun setLine(text: String) {
        line.text = text
    }

    fun setDot(color: Int) {
        dot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }

    fun setStopVisible(on: Boolean) {
        stop.visibility = if (on) VISIBLE else GONE
    }

    /** A 48 dp touch target with a 36 dp disc, as the phone-control capsule's keys. */
    private class StopKey(
        context: Context,
        private val palette: OverlayPalette,
        onClick: () -> Unit,
    ) : View(context) {
        private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.tonal }
        private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.onTonal }

        init {
            isClickable = true
            contentDescription = AppStrings.get(context, R.string.pet_stop)
            setOnClickListener { onClick() }
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            canvas.drawCircle(cx, cy, context.dp(18f).toFloat(), disc)
            val s = context.dp(5f).toFloat()
            canvas.drawRoundRect(cx - s, cy - s, cx + s, cy + s, context.dp(1f).toFloat(), context.dp(1f).toFloat(), glyph)
        }
    }
}