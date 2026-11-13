package io.github.ccch1mneyyy.dsh_app.pet

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/** One row of the floating strip's bookmark stack. */
internal data class OrbBookmark(
    val id: String,
    /** `background` (this phone) or `device` (a Mesh task). */
    val kind: String,
    val sessionId: String,
    val nodeId: String?,
    val title: String,
    /** running | waiting | completed | stopped | ended */
    val status: String,
    val startedAt: Long,
    val endedAt: Long?,
    val unread: Boolean,
    val deviceName: String?,
) {
    val active: Boolean get() = status == "running" || status == "waiting"
}

internal data class BookmarksPlacement(
    val window: ScreenRect,
    val chipW: Int,
    val chipH: Int,
    val gap: Int,
)

/**
 * The bookmark cluster sits next to the strip (or the input bar) on the side
 * facing away from the screen edge: above when the anchor is in the lower
 * half, below when it is in the upper half. Two chips per row, at most four
 * visible (the fourth becomes `+N`); the whole cluster stays on screen.
 */
internal object OrbBookmarksGeometry {
    const val CHIP_H_DP = 40f
    const val MAX_CHIP_W_DP = 150f
    const val GAP_DP = 6f
    const val MARGIN_DP = 8f
    const val COLUMNS = 2
    const val MAX_VISIBLE = 4

    fun place(area: SafeArea, anchor: ScreenRect, side: Side, count: Int, density: Float): BookmarksPlacement {
        val gap = (GAP_DP * density).roundToInt()
        val margin = (MARGIN_DP * density).roundToInt()
        val chipH = (CHIP_H_DP * density).roundToInt()
        val available = area.screenW - area.left - area.right - margin * 2 - gap
        val chipW = min((MAX_CHIP_W_DP * density).roundToInt(), available / COLUMNS).coerceAtLeast((80f * density).roundToInt())
        val cols = if (count.coerceAtMost(MAX_VISIBLE) <= 1) 1 else COLUMNS
        val rows = ceil(count.coerceAtMost(MAX_VISIBLE).toFloat() / COLUMNS).toInt().coerceAtLeast(1)
        val w = cols * chipW + (cols - 1) * gap
        val h = rows * chipH + (rows - 1) * gap
        val x = when (side) {
            Side.RIGHT -> anchor.right - w
            Side.LEFT -> anchor.left
        }.coerceIn(
            area.left + margin,
            maxOf(area.left + margin, area.screenW - area.right - margin - w),
        )
        val above = anchor.centerY > area.screenH / 2
        val y = if (above) {
            anchor.top - h - gap
        } else {
            anchor.bottom + gap
        }.coerceIn(
            area.top + margin,
            maxOf(area.top + margin, area.screenH - area.bottom - margin - h),
        )
        return BookmarksPlacement(ScreenRect(x, y, x + w, y + h), chipW, chipH, gap)
    }
}

/**
 * The bookmark window: one touchable, non-focusable overlay holding the chips.
 * It follows the strip/input bar; hidden with it.
 */
internal class OrbBookmarks(
    private val ctx: Context,
    private val windows: OverlayWindowOwner,
    private val params: (w: Int, h: Int) -> WindowManager.LayoutParams,
    private val add: (OverlayWindowOwner.Ticket, View, WindowManager.LayoutParams) -> Boolean,
    private val update: (View, WindowManager.LayoutParams) -> Unit,
    private val remove: (View) -> Unit,
    private val onOpen: (OrbBookmark) -> Unit,
    private val onDismiss: (OrbBookmark) -> Unit,
    private val onOverflow: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var view: OrbBookmarksView? = null
    private var p: WindowManager.LayoutParams? = null
    private var items: List<OrbBookmark> = emptyList()
    private var tick: Runnable? = null
    private var lastChipW = 0
    private var lastChipH = 0
    private var lastGap = 0
    private var lastSide = Side.RIGHT

    val visible: Boolean get() = view != null

    fun show(next: List<OrbBookmark>, anchor: ScreenRect, area: SafeArea, side: Side, animate: Boolean) {
        if (next.isEmpty()) {
            hide()
            return
        }
        val changed = next != items
        items = next
        val place = OrbBookmarksGeometry.place(
            area, anchor, side, next.size, ctx.resources.displayMetrics.density,
        )
        val existing = view
        val wp = p
        if (existing != null && wp != null) {
            wp.x = place.window.left
            wp.y = place.window.top
            wp.width = place.window.width
            wp.height = place.window.height
            update(existing, wp)
            existing.setSide(side)
            if (changed || place.chipW != lastChipW || place.chipH != lastChipH ||
                place.gap != lastGap || side != lastSide
            ) {
                existing.bind(next, place.chipW, place.chipH, place.gap)
                lastChipW = place.chipW
                lastChipH = place.chipH
                lastGap = place.gap
                lastSide = side
            }
            startTick()
            return
        }
        val ticket = windows.claim(OverlayWindowOwner.Role.BOOKMARKS) ?: return
        val v = OrbBookmarksView(ctx, onOpen, onDismiss, onOverflow).apply { setSide(side) }
        v.bind(next, place.chipW, place.chipH, place.gap)
        val fresh = params(place.window.width, place.window.height).apply {
            x = place.window.left
            y = place.window.top
        }
        if (!add(ticket, v, fresh)) return
        view = v
        p = fresh
        lastChipW = place.chipW
        lastChipH = place.chipH
        lastGap = place.gap
        lastSide = side
        if (animate) {
            v.alpha = 0f
            v.animate().alpha(1f).setDuration(140).start()
        }
        startTick()
        Log.i(TAG, "bookmarks add x=${place.window.left} y=${place.window.top} ${place.window.width}x${place.window.height} chips=${next.size}")
    }

    fun hide() {
        stopTick()
        view?.let(remove)
        view = null
        p = null
    }

    fun describe(): String {
        val v = view ?: return "bookmarks=none"
        val wp = p ?: return "bookmarks=${items.size}"
        return "bookmarks=${items.size} at=${wp.x},${wp.y} ${wp.width}x${wp.height}"
    }

    private fun startTick() {
        stopTick()
        if (items.none { it.active }) return
        val r = object : Runnable {
            override fun run() {
                view?.refreshTimes()
                main.postDelayed(this, 30_000)
            }
        }
        tick = r
        main.postDelayed(r, 30_000)
    }

    private fun stopTick() {
        tick?.let(main::removeCallbacks)
        tick = null
    }

    private companion object {
        const val TAG = "DshPet"
    }
}