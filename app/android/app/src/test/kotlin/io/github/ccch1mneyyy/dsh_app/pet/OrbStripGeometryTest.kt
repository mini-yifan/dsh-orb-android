package io.github.ccch1mneyyy.dsh_app.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbStripGeometryTest {
    private val area = SafeArea(screenW = 1080, screenH = 2400, left = 0, top = 60, right = 0, bottom = 120)

    private fun place(
        body: ScreenRect,
        frame: ScreenRect,
        side: Side = Side.RIGHT,
        variant: SeamVariant = SeamVariant.TONGUE,
        density: Float = 1f,
    ) = OrbStripGeometry.place(area, body, frame, side, variant, density)

    @Test
    fun `right dock grows left from the pet frame and caps its width`() {
        val p = place(ScreenRect(920, 1020, 1000, 1100), ScreenRect(900, 1000, 1020, 1120))

        assertEquals(900, p.strip.right)
        assertEquals(580, p.strip.left)
        assertEquals(320, p.strip.width)
        assertEquals(48, p.strip.height)
        assertEquals(1036, p.strip.top)
        assertEquals(Side.RIGHT, p.side)
        assertEquals(12, p.nearInset)
        assertEquals(48, p.startWidth)
        assertTrue(!p.behind)
    }

    @Test
    fun `left dock mirrors the same geometry`() {
        val p = place(
            ScreenRect(80, 1020, 160, 1100),
            ScreenRect(60, 1000, 180, 1120),
            side = Side.LEFT,
        )

        assertEquals(180, p.strip.left)
        assertEquals(500, p.strip.right)
        assertEquals(320, p.strip.width)
        assertEquals(1036, p.strip.top)
        assertEquals(Side.LEFT, p.side)
    }

    @Test
    fun `the tongue bridges the frame edge into the body`() {
        val body = ScreenRect(920, 1020, 1000, 1100)
        val p = place(body, ScreenRect(900, 1000, 1020, 1120))

        val tongue = p.tongue!!
        assertEquals(898, tongue.left)
        assertEquals(body.left + 8, tongue.right)
        assertEquals(1051, tongue.top)
        assertEquals(1069, tongue.bottom)
    }

    @Test
    fun `the left dock tongue points into the body`() {
        val body = ScreenRect(80, 1020, 160, 1100)
        val p = place(body, ScreenRect(60, 1000, 180, 1120), side = Side.LEFT)

        val tongue = p.tongue!!
        assertEquals(body.right - 8, tongue.left)
        assertEquals(182, tongue.right)
    }

    @Test
    fun `tuck variants sit under the pet and ask to be behind it`() {
        val p = place(
            ScreenRect(920, 1020, 1000, 1100),
            ScreenRect(900, 1000, 1020, 1120),
            variant = SeamVariant.TUCK28,
        )

        assertEquals(928, p.strip.right)
        assertEquals(608, p.strip.left)
        assertEquals(40, p.nearInset)
        assertTrue(p.behind)
        assertNull(p.tongue)
    }

    @Test
    fun `edge variant has no tongue`() {
        val p = place(
            ScreenRect(920, 1020, 1000, 1100),
            ScreenRect(900, 1000, 1020, 1120),
            variant = SeamVariant.EDGE,
        )

        assertNull(p.tongue)
        assertEquals(900, p.strip.right)
    }

    @Test
    fun `the strip stays inside the safe area at the top`() {
        val p = place(ScreenRect(920, 20, 1000, 60), ScreenRect(900, 0, 1020, 120))

        assertEquals(72, p.strip.top)
    }

    @Test
    fun `a narrow screen keeps the start stub inside the strip`() {
        val narrow = SafeArea(screenW = 120, screenH = 2400, left = 0, top = 60, right = 0, bottom = 120)
        val p = OrbStripGeometry.place(
            narrow,
            ScreenRect(60, 1020, 100, 1100),
            ScreenRect(50, 1000, 110, 1120),
            Side.RIGHT,
            SeamVariant.TONGUE,
            1f,
        )

        assertEquals(p.strip.width, p.startWidth)
    }

    @Test
    fun `density scales the strip`() {
        val p = place(
            ScreenRect(1920, 2040, 2000, 2200),
            ScreenRect(1800, 2000, 2040, 2240),
            density = 2f,
        )

        assertEquals(96, p.strip.height)
        assertEquals(24, p.nearInset)
        assertEquals(96, p.startWidth)
    }
}