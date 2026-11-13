package io.github.ccch1mneyyy.dsh_app.pet

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The bookmark stack's window content: two chips per row, up to four visible,
 * the fourth becoming `+N`. The rows are aligned to the strip's side; the
 * chips themselves are touchable even though the window is not focusable.
 */
internal class OrbBookmarksView(
    context: Context,
    private val onOpen: (OrbBookmark) -> Unit,
    private val onDismiss: (OrbBookmark) -> Unit,
    private val onOverflow: () -> Unit,
) : LinearLayout(context) {
    private val palette = OverlayPalette.of(context)
    private val chips = mutableListOf<OrbBookmarkChip>()
    private var side = Side.RIGHT

    init {
        orientation = VERTICAL
        gravity = Gravity.END
    }

    fun setSide(side: Side) {
        this.side = side
        gravity = if (side == Side.RIGHT) Gravity.END else Gravity.START
    }

    fun bind(items: List<OrbBookmark>, chipW: Int, chipH: Int, gap: Int) {
        removeAllViews()
        chips.clear()
        val visible = if (items.size > OrbBookmarksGeometry.MAX_VISIBLE) {
            items.take(OrbBookmarksGeometry.MAX_VISIBLE - 1)
        } else {
            items
        }
        val overflow = items.size - visible.size
        val entries = buildList<(LinearLayout.LayoutParams) -> View> {
            for (item in visible) {
                add { lp ->
                    OrbBookmarkChip(context, palette, item, onOpen, onDismiss).also { chip ->
                        chips.add(chip)
                        chip.layoutParams = lp
                    }
                }
            }
            if (overflow > 0) {
                add { lp ->
                    OverflowChip(context, palette, overflow, onOverflow).also { it.layoutParams = lp }
                }
            }
        }
        var index = 0
        while (index < entries.size) {
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            for (col in 0 until OrbBookmarksGeometry.COLUMNS) {
                if (index >= entries.size) break
                val lp = LayoutParams(chipW, chipH).apply {
                    if (col > 0) marginStart = gap
                }
                row.addView(entries[index](lp))
                index++
            }
            addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, chipH).apply {
                if (childCount > 0) topMargin = gap
            })
        }
    }

    fun refreshTimes() {
        for (chip in chips) chip.refreshTime()
    }
}

/** One bookmark row: color bar, state icon, title, elapsed, and × for an unread finish. */
private class OrbBookmarkChip(
    context: Context,
    palette: OverlayPalette,
    private val item: OrbBookmark,
    onOpen: (OrbBookmark) -> Unit,
    onDismiss: (OrbBookmark) -> Unit,
) : LinearLayout(context) {
    private val time = TextView(context).apply {
        textSize = 10f
        setTextColor(palette.muted)
        includeFontPadding = false
    }
    private val dismiss = TextView(context).apply {
        text = "×"
        textSize = 16f
        gravity = Gravity.CENTER
        setTextColor(palette.muted)
        contentDescription = "移除"
        visibility = if (item.unread && !item.active) VISIBLE else GONE
        setOnClickListener { onDismiss(item) }
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(8f), 0, context.dp(4f), 0)
        background = GradientDrawable().apply {
            cornerRadius = context.dp(12f).toFloat()
            setColor(palette.fill)
            setStroke(context.dp(1f).coerceAtLeast(1), palette.stroke)
        }
        val color = when {
            item.kind == "device" -> 0xFF7E57C2.toInt()
            item.status == "completed" -> 0xFF2E7D32.toInt()
            item.status == "stopped" -> palette.muted
            item.status == "ended" -> palette.danger
            item.status == "waiting" -> 0xFFE0A400.toInt()
            else -> palette.accent
        }
        addView(View(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = context.dp(1.5f).toFloat()
                setColor(color)
            }
        }, LayoutParams(context.dp(3f), LayoutParams.MATCH_PARENT).apply { marginEnd = context.dp(6f) })
        addView(BookmarkStatusIcon(context, item.status, color), LayoutParams(context.dp(14f), context.dp(14f)).apply {
            marginEnd = context.dp(6f)
        })
        val title = TextView(context).apply {
            textSize = 11f
            setTextColor(palette.ink)
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            this.text = if (item.deviceName != null) "${item.deviceName} · ${item.title}" else item.title
        }
        addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(time, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = context.dp(4f)
        })
        addView(dismiss, LayoutParams(context.dp(24f), LayoutParams.MATCH_PARENT))
        setOnClickListener { onOpen(item) }
        refreshTime()
    }

    fun refreshTime() {
        time.text = elapsed(item.startedAt, item.endedAt)
    }

    private fun elapsed(startedAt: Long, endedAt: Long?): String {
        val total = ((endedAt ?: System.currentTimeMillis()) - startedAt).coerceAtLeast(0)
        val minutes = total / 60_000
        return when {
            minutes < 1 -> "<1m"
            minutes < 100 -> "${minutes}m"
            else -> "${minutes / 60}h${(minutes % 60).toString().padStart(2, '0')}"
        }
    }
}

private class OverflowChip(
    context: Context,
    palette: OverlayPalette,
    count: Int,
    onOverflow: () -> Unit,
) : TextView(context) {
    init {
        text = "＋$count"
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(palette.ink)
        background = GradientDrawable().apply {
            cornerRadius = context.dp(12f).toFloat()
            setColor(palette.fill)
            setStroke(context.dp(1f).coerceAtLeast(1), palette.stroke)
        }
        contentDescription = "还有 $count 个任务"
        setOnClickListener { onOverflow() }
    }
}

/** A 14 dp state glyph; running spins, the rest are static. */
private class BookmarkStatusIcon(
    context: Context,
    private val status: String,
    private val color: Int,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1.6f).toFloat()
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private var phase = 0f
    private var spin: ValueAnimator? = null

    init {
        if (status == "running") startSpin()
    }

    private fun startSpin() {
        if (spin != null) return
        spin = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (status == "running") startSpin()
    }

    override fun onDetachedFromWindow() {
        spin?.cancel()
        spin = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(cx, cy) - context.dp(1f)
        when (status) {
            "running" -> {
                canvas.save()
                canvas.rotate(phase, cx, cy)
                canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -90f, 280f, false, paint)
                canvas.restore()
            }
            "waiting" -> {
                canvas.drawCircle(cx, cy, r, paint)
                canvas.drawLine(cx, cy - r / 2, cx, cy + r / 4, paint)
                canvas.drawPoint(cx, cy + r / 2, fill)
            }
            "completed" -> {
                canvas.drawLine(cx - r * 0.8f, cy, cx - r * 0.1f, cy + r * 0.7f, paint)
                canvas.drawLine(cx - r * 0.1f, cy + r * 0.7f, cx + r * 0.9f, cy - r * 0.7f, paint)
            }
            "stopped" -> canvas.drawRoundRect(cx - r * 0.6f, cy - r * 0.6f, cx + r * 0.6f, cy + r * 0.6f, context.dp(1f).toFloat(), context.dp(1f).toFloat(), fill)
            else -> {
                canvas.drawLine(cx, cy - r * 0.8f, cx, cy + r * 0.2f, paint)
                canvas.drawPoint(cx, cy + r * 0.7f, fill)
            }
        }
    }
}