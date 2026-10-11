package com.ashorts.blocker.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PatchColorMathTest {

    private val cell = IntBounds(216, 2112, 432, 2256)

    private fun inside(x: Int, y: Int, b: IntBounds) =
        x >= b.left && x < b.right && y >= b.top && y < b.bottom

    @Test
    fun samplePointsNeverReadThePatchItself() {
        val points = PatchColorMath.samplePoints(cell, 1080, 2400)
        assertTrue(points.size >= 4)
        points.forEach { (x, y) ->
            assertFalse("($x,$y) is inside the patch", inside(x, y, cell))
            assertTrue("($x,$y) is outside the bar rows", y in cell.top until cell.bottom)
        }
    }

    @Test
    fun samplePointsStayOnScreenAndOutsideCellAtTheScreenEdge() {
        val leftmost = IntBounds(0, 2112, 216, 2256)
        val points = PatchColorMath.samplePoints(leftmost, 1080, 2400)
        assertTrue(points.isNotEmpty())
        points.forEach { (x, y) ->
            assertTrue(x in 0 until 1080 && y in 0 until 2400)
            assertFalse(inside(x, y, leftmost))
        }
    }

    @Test
    fun medianIgnoresGlyphFragments() {
        val bar = 0xFF121212.toInt() // Instagram's "lighter black"
        val glyph = 0xFFF5F5F5.toInt()
        val picked = PatchColorMath.pickColor(listOf(bar, bar, glyph, bar, bar, glyph))
        assertEquals(bar, picked)
    }

    @Test
    fun dropsTransparentPixelsAndReturnsOpaque() {
        val bar = 0xFF262626.toInt()
        assertEquals(bar, PatchColorMath.pickColor(listOf(0x00000000, bar, 0x80FFFFFF.toInt())))
        assertNull(PatchColorMath.pickColor(listOf(0x00000000)))
        assertNull(PatchColorMath.pickColor(emptyList()))
    }

    @Test
    fun hexFormatsRgbOnly() {
        assertEquals("#121212", PatchColorMath.hex(0xFF121212.toInt()))
    }
}
