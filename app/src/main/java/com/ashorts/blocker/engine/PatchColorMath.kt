package com.ashorts.blocker.engine

/**
 * Pure math for picking the patch color (unit-tested on the JVM).
 *
 * The color is *sampled* from Instagram's own bottom bar via an accessibility screenshot, so
 * the patch matches whatever shade Instagram actually uses ("lighter black" dark themes, warm
 * grays, future rebrands) without any hardcoded guesses. To avoid reading our own patch, all
 * sample points sit horizontally OUTSIDE the covered cell, in the background between the
 * neighboring tabs' glyphs.
 */
object PatchColorMath {

    /** Candidate pixels: outside the cell horizontally, at three heights of the bar. */
    fun samplePoints(cell: IntBounds, screenWidth: Int, screenHeight: Int): List<Pair<Int, Int>> {
        if (screenWidth < 3 || screenHeight < 3) return emptyList()
        val midY = cell.top + cell.height / 2
        val quarterY = cell.top + cell.height / 4
        val threeQuarterY = cell.top + 3 * cell.height / 4
        val raw = listOf(
            cell.left - 24 to midY,
            cell.left - 8 to midY,
            cell.right + 8 to midY,
            cell.right + 24 to midY,
            cell.left - 8 to quarterY,
            cell.right + 8 to threeQuarterY
        )
        return raw
            .map { (x, y) -> x.coerceIn(1, screenWidth - 2) to y.coerceIn(1, screenHeight - 2) }
            // Clamping can push a point back inside the cell (cell touching a screen edge);
            // those would read our own patch.
            .filterNot { (x, y) -> x >= cell.left && x < cell.right && y >= cell.top && y < cell.bottom }
            .distinct()
    }

    /**
     * Median RGB over the candidate pixels. The median ignores glyph fragments and stray
     * anti-aliased edges, and drops non-opaque pixels. Returns an opaque ARGB color.
     */
    fun pickColor(candidates: List<Int>): Int? {
        val usable = candidates.filter { (it ushr 24) == 0xFF }
        if (usable.isEmpty()) return null

        fun median(values: List<Int>): Int {
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid]
            else (sorted[mid - 1] + sorted[mid]) / 2
        }

        val r = median(usable.map { (it shr 16) and 0xFF })
        val g = median(usable.map { (it shr 8) and 0xFF })
        val b = median(usable.map { it and 0xFF })
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun hex(color: Int): String = "#%06X".format(color and 0xFFFFFF)
}
