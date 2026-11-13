package io.github.ccch1mneyyy.dsh_app.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbBookmarksGeometryTest {
    private val area = SafeArea(screenW = 1080, screenH = 2400, left = 0, top = 60, right = 0, bottom = 120)

    @Test
    fun `two chips above a low strip stay on screen`() {
        val anchor = ScreenRect(100, 1800, 900, 1848)
        val p = OrbBookmarksGeometry.place(area, anchor, Side.RIGHT, 2, 1f)

        assertEquals(150, p.chipW)
        assertEquals(40, p.chipH)
        assertEquals(6, p.gap)
        assertEquals(306, p.window.width)
        assertEquals(40, p.window.height)
        assertEquals(594, p.window.left)
        assertEquals(1754, p.window.top)
    }

    @Test
    fun `a high strip puts the chips below`() {
        val anchor = ScreenRect(100, 200, 900, 248)
        val p = OrbBookmarksGeometry.place(area, anchor, Side.LEFT, 2, 1f)

        assertEquals(254, p.window.top)
        assertEquals(100, p.window.left)
    }

    @Test
    fun `one chip uses one column`() {
        val anchor = ScreenRect(100, 1800, 900, 1848)
        val p = OrbBookmarksGeometry.place(area, anchor, Side.RIGHT, 1, 1f)

        assertEquals(150, p.window.width)
        assertEquals(750, p.window.left)
    }

    @Test
    fun `four chips use two rows`() {
        val anchor = ScreenRect(100, 1800, 900, 1848)
        val p = OrbBookmarksGeometry.place(area, anchor, Side.RIGHT, 4, 1f)

        assertEquals(86, p.window.height)
        assertEquals(2, (p.window.height + p.gap) / (p.chipH + p.gap))
    }

    @Test
    fun `the cluster is clamped inside the safe area`() {
        val anchor = ScreenRect(0, 1800, 120, 1848)
        val p = OrbBookmarksGeometry.place(area, anchor, Side.RIGHT, 2, 1f)

        assertTrue(p.window.left >= 8)
        assertTrue(p.window.right <= area.screenW - 8)
        assertTrue(p.window.top >= area.top + 8)
        assertTrue(p.window.bottom <= area.screenH - area.bottom - 8)
    }

    @Test
    fun `density scales the chips`() {
        val anchor = ScreenRect(200, 3600, 1800, 3696)
        val p = OrbBookmarksGeometry.place(area.copy(screenW = 2160, screenH = 4800), anchor, Side.RIGHT, 2, 2f)

        assertEquals(80, p.chipH)
        assertEquals(12, p.gap)
        assertEquals(300, p.chipW)
    }
}