package com.ashorts.blocker.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PatchColorMathTest {

    private val cell = IntBounds(216, 2200, 432, 2340)

    private fun inside(x: Int, y: Int) = x >= cell.left && x < cell.right && y >= cell.top && y < cell.bottom

    @Test
    fun `sample points are never inside the covered cell`() {
        val points = PatchColorMath.samplePoints(cell, 1080, 2400)
        assertTrue(points.isNotEmpty())
        assertFalse(points.any { (x, y) -> inside(x, y) })
    }

    @Test
    fun `sample points sit on both sides at the height of the bar`() {
        val points = PatchColorMath.samplePoints(cell, 1080, 2400)
        assertTrue(points.any { (x, _) -> x < cell.left })
        assertTrue(points.any { (x, _) -> x >= cell.right })
        assertTrue(points.all { (_, y) -> y in cell.top until cell.bottom })
    }

    @Test
    fun `cell at the screen edge still samples only outside it`() {
        val edge = IntBounds(0, 2200, 216, 2340)
        val points = PatchColorMath.samplePoints(edge, 1080, 2400)
        assertTrue(points.isNotEmpty())
        assertTrue(points.all { (x, _) -> x >= edge.right })
    }

    @Test
    fun `majority color wins over icon fragments`() {
        val bar = 0xFF0C0F14.toInt()
        val samples = List(14) { bar } + List(4) { 0xFFFFFFFF.toInt() }
        assertEquals(bar, PatchColorMath.pickColor(samples))
    }

    @Test
    fun `near identical shades are averaged into one color`() {
        val samples = listOf(0xFFFFFFFF.toInt(), 0xFFFEFEFE.toInt(), 0xFFFFFFFF.toInt(), 0xFFFEFEFE.toInt())
        assertEquals(0xFFFEFEFE.toInt(), PatchColorMath.pickColor(samples))
    }

    @Test
    fun `no clear majority keeps the previous color`() {
        val samples = listOf(0xFF102030.toInt(), 0xFF908070.toInt(), 0xFFF0E0D0.toInt(), 0xFF00FF00.toInt())
        assertNull(PatchColorMath.pickColor(samples))
    }

    @Test
    fun `pure black is rejected as a protected or blank frame`() {
        assertNull(PatchColorMath.pickColor(List(10) { 0xFF000000.toInt() }))
    }

    @Test
    fun `Instagram bar colors are classified by brightness`() {
        assertTrue(PatchColorMath.isDark(0xFF0C0F14.toInt()))
        assertTrue(PatchColorMath.isDark(0xFF000000.toInt()))
        assertFalse(PatchColorMath.isDark(0xFFFFFFFF.toInt()))
        assertFalse(PatchColorMath.isDark(0xFFFAFAFA.toInt()))
    }

    @Test
    fun `transparent pixels are ignored`() {
        assertNull(PatchColorMath.pickColor(List(10) { 0x00FFFFFF }))
        assertEquals(0xFFFFFFFF.toInt(), PatchColorMath.pickColor(List(5) { 0x00123456 } + List(3) { -1 }))
    }
}
