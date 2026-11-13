package io.github.ccch1mneyyy.dsh_app.pet

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max

/**
 * The hold-to-talk capsule: a waveform, an elapsed time and one hint line. It never takes touch
 * (the gesture belongs to the pet); it only says what the finger is doing.
 */
internal class OrbVoiceView(context: Context) : LinearLayout(context) {
    private val palette = OverlayPalette.of(context)
    private val wave = WaveView(context, palette)
    private val time = TextView(context).apply {
        textSize = 12f
        setTextColor(palette.muted)
        includeFontPadding = false
    }
    private val hint = TextView(context).apply {
        textSize = 12f
        setTextColor(palette.muted)
        includeFontPadding = false
        maxLines = 2
    }

    init {
        orientation = VERTICAL
        setPadding(context.dp(14f), context.dp(9f), context.dp(14f), context.dp(9f))
        background = GradientDrawable().apply {
            cornerRadius = context.dp(20f).toFloat()
            setColor(palette.fill)
            setStroke(context.dp(1f).coerceAtLeast(1), palette.stroke)
        }
        val top = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(wave, LayoutParams(0, context.dp(26f), 1f))
        top.addView(time, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = context.dp(10f)
        })
        addView(top, LayoutParams(LayoutParams.MATCH_PARENT, context.dp(26f)))
        addView(hint, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(4f)
        })
    }

    fun setLevel(level: Double) {
        wave.push(level.toFloat())
    }

    fun setElapsed(ms: Long) {
        val total = (ms / 1000).coerceAtLeast(0)
        time.text = "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
    }

    fun setHint(text: String, danger: Boolean = false) {
        hint.text = text
        hint.setTextColor(if (danger) palette.danger else palette.muted)
    }

    /** A simple centered bar waveform; one bar per ~60 ms level report. */
    private class WaveView(context: Context, palette: OverlayPalette) : View(context) {
        private val levels = FloatArray(28)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.accent }

        fun push(level: Float) {
            for (i in 0 until levels.size - 1) levels[i] = levels[i + 1]
            levels[levels.size - 1] = level.coerceIn(0f, 1f)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (width <= 0 || height <= 0) return
            val gap = context.dp(3f).toFloat()
            val bar = max(2f, (width - gap * (levels.size - 1)) / levels.size)
            val cy = height / 2f
            val minH = context.dp(2f).toFloat()
            val maxH = height.toFloat()
            var x = (width - (bar * levels.size + gap * (levels.size - 1))) / 2f
            for (level in levels) {
                val h = minH + (maxH - minH) * level
                canvas.drawRoundRect(x, cy - h / 2f, x + bar, cy + h / 2f, bar / 2f, bar / 2f, paint)
                x += bar + gap
            }
        }
    }
}