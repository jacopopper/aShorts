package com.ashorts.blocker.engine

/**
 * Picks the patch color from the bar pixels right next to the covered cell, so the patch
 * matches whatever Instagram is drawing (system theme, Instagram's own theme setting, or a
 * future redesign). Every sample point is horizontally outside the cell, so our own patch is
 * never read back.
 */
object PatchColorMath {

    /** Distances from the cell edge: in the gap between tabs, well clear of the neighbor icons. */
    private val EDGE_OFFSETS = intArrayOf(6, 14, 24)

    fun samplePoints(cell: IntBounds, screenWidth: Int, screenHeight: Int): List<Pair<Int, Int>> {
        if (screenWidth < 3 || screenHeight < 3 || cell.height <= 0) return emptyList()
        val rows = listOf(
            cell.top + cell.height / 4,
            cell.top + cell.height / 2,
            cell.top + 3 * cell.height / 4
        )
        val points = ArrayList<Pair<Int, Int>>(rows.size * EDGE_OFFSETS.size * 2)
        for (y in rows) {
            for (d in EDGE_OFFSETS) {
                points += cell.left - d to y
                points += cell.right - 1 + d to y
            }
        }
        return points
            .map { (x, y) -> x.coerceIn(0, screenWidth - 1) to y.coerceIn(0, screenHeight - 1) }
            // Clamping can push a point back inside the cell when it touches a screen edge.
            .filterNot { (x, y) -> x >= cell.left && x < cell.right && y >= cell.top && y < cell.bottom }
            .distinct()
    }

    /**
     * The most common color among the samples (icon edges and badges lose the vote), or null
     * when there is no clear majority or the result is pure black, which is what a protected
     * (FLAG_SECURE) window or a not-yet-drawn frame looks like; Instagram's dark bar is not
     * pure black.
     */
    fun pickColor(samples: List<Int>): Int? {
        val opaque = samples.filter { (it ushr 24) == 0xFF }
        if (opaque.isEmpty()) return null
        val buckets = opaque.groupBy { quantize(it) }
        val winner = buckets.maxByOrNull { it.value.size }?.value ?: return null
        if (winner.size * 2 < opaque.size) return null
        val color = average(winner)
        return if (color and 0xFFFFFF == 0) null else color
    }

    /** Groups near-identical shades (compression noise, dithering) into one vote. */
    private fun quantize(color: Int): Int =
        ((color shr 16) and 0xF8 shl 16) or ((color shr 8) and 0xF8 shl 8) or (color and 0xF8)

    private fun average(colors: List<Int>): Int {
        var r = 0
        var g = 0
        var b = 0
        for (c in colors) {
            r += (c shr 16) and 0xFF
            g += (c shr 8) and 0xFF
            b += c and 0xFF
        }
        val n = colors.size
        return (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
    }

    fun isDark(color: Int): Boolean {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return 299 * r + 587 * g + 114 * b < 128_000
    }

    fun hex(color: Int): String = "#%06X".format(color and 0xFFFFFF)
}
