package io.github.ccch1mneyyy.dsh_app.pet

import kotlin.math.roundToInt

/**
 * How the strip meets the pet. [TONGUE] is the product default: the strip starts at the pet's
 * touch frame and a small non-touchable tongue bridges the gap into the body. [EDGE] has no
 * tongue. [TUCK12]/[TUCK28] put the strip's near edge under the pet by that much and need the
 * strip window to be *behind* the pet (the E1 probe checks whether the host allows it).
 */
internal enum class SeamVariant { TONGUE, EDGE, TUCK12, TUCK28 }

/**
 * Where the strip and its tongue go (physical px). [nearInset] is the padding on the side facing
 * the pet, so the status text is never under the pet's touch frame; [startWidth] is the small
 * round stub the emergence animation starts from; [behind] asks the window host to put the strip
 * under the pet (only meaningful for the tuck variants).
 */
internal data class StripPlacement(
    val strip: ScreenRect,
    val tongue: ScreenRect?,
    val side: Side,
    val nearInset: Int,
    val startWidth: Int,
    val behind: Boolean,
)

/**
 * The strip grows out of the docked pet, horizontally towards the middle of the screen:
 * right-docked → the strip's right edge is at the pet's touch frame (or tucked under it), the
 * left edge moves out; left-docked → mirrored. The far end is capped at [MAX_WIDTH_DP] and the
 * whole thing stays inside [SafeArea].
 *
 * Pure: the overlay computes the pet's body/frame; the probe and the JVM tests use this directly.
 */
internal object OrbStripGeometry {
    const val HEIGHT_DP = 48f
    const val MAX_WIDTH_DP = 320f
    const val MARGIN_DP = 12f
    const val TONGUE_THICK_DP = 18f
    const val TONGUE_OVERLAP_DP = 8f
    const val NEAR_INSET_DP = 12f
    const val START_WIDTH_DP = 48f

    fun place(
        area: SafeArea,
        body: ScreenRect,
        frame: ScreenRect,
        side: Side,
        variant: SeamVariant,
        density: Float,
    ): StripPlacement {
        val h = (HEIGHT_DP * density).roundToInt()
        val margin = (MARGIN_DP * density).roundToInt()
        val maxW = (MAX_WIDTH_DP * density).roundToInt()
        val tuck = when (variant) {
            SeamVariant.TUCK12 -> (12f * density).roundToInt()
            SeamVariant.TUCK28 -> (28f * density).roundToInt()
            else -> 0
        }
        val nearInset = (NEAR_INSET_DP * density).roundToInt() + tuck
        val minY = area.top + margin
        val maxY = maxOf(minY, area.screenH - area.bottom - margin - h)
        val y = (body.centerY - h / 2).coerceIn(minY, maxY)
        val startW = (START_WIDTH_DP * density).roundToInt()

        val strip: ScreenRect
        val tongue: ScreenRect?
        when (side) {
            Side.RIGHT -> {
                val near = frame.left + tuck
                val far = maxOf(area.left + margin, near - maxW)
                strip = ScreenRect(far, y, near, y + h)
                tongue = if (variant == SeamVariant.TONGUE) {
                    val from = near - (2f * density).roundToInt()
                    val to = maxOf(near + (4f * density).roundToInt(), body.left + (TONGUE_OVERLAP_DP * density).roundToInt())
                    val thick = (TONGUE_THICK_DP * density).roundToInt()
                    ScreenRect(from, body.centerY - thick / 2, to, body.centerY - thick / 2 + thick)
                } else null
            }
            Side.LEFT -> {
                val near = frame.right - tuck
                val far = minOf(area.screenW - area.right - margin, near + maxW)
                strip = ScreenRect(near, y, far, y + h)
                tongue = if (variant == SeamVariant.TONGUE) {
                    val to = near + (2f * density).roundToInt()
                    val from = minOf(near - (4f * density).roundToInt(), body.right - (TONGUE_OVERLAP_DP * density).roundToInt())
                    val thick = (TONGUE_THICK_DP * density).roundToInt()
                    ScreenRect(from, body.centerY - thick / 2, to, body.centerY - thick / 2 + thick)
                } else null
            }
        }
        return StripPlacement(
            strip = strip,
            tongue = tongue,
            side = side,
            nearInset = nearInset,
            startWidth = startW.coerceIn(1, maxOf(1, strip.width)),
            behind = tuck > 0,
        )
    }
}