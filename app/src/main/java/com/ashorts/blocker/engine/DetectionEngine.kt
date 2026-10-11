package com.ashorts.blocker.engine

/**
 * Scans a window's node tree and decides what should be blocked.
 *
 * The engine is pure Kotlin (no Android imports) so it can be fully unit-tested on the JVM.
 * Traversal is breadth-first with hard caps on visited nodes, depth, and children per node so a
 * scan costs at most a few milliseconds even inside Instagram's huge view hierarchies, and it
 * stops early as soon as everything it looks for has been found.
 */
class DetectionEngine(private val rules: AppBlockRules) {

    data class ScanResult(
        /** True when the full-screen Reels feed itself is visible. */
        val reelsScreenDetected: Boolean,
        /** Bounds of the Reels tab in the bottom bar, if present (and the tab should be hidden). */
        val reelsTabBounds: IntBounds?,
        /** Human-readable list of what matched, used by the detection log. */
        val matched: List<String>
    )

    fun scan(root: NodeHandle, screenWidth: Int, screenHeight: Int): ScanResult {
        val matched = mutableListOf<String>()
        var screenHit = false
        var tabBounds: IntBounds? = null

        val minTabTop = (screenHeight * rules.tabRegionTopFraction).toInt()
        val maxTabHeight = (screenHeight * rules.tabMaxHeightFraction).toInt()
        val maxTabWidth = (screenWidth * rules.tabMaxWidthFraction).toInt()

        val queue = ArrayDeque<NodeHandle>()
        queue.add(root)
        var visited = 0

        while (queue.isNotEmpty() && visited < rules.maxNodes) {
            val node = queue.removeFirst()
            visited++

            val id = node.viewId
            val desc = node.contentDescription?.toString()

            if (!screenHit) {
                if (id != null && rules.screenIdPatterns.any { it.containsMatchIn(id) }) {
                    screenHit = true
                    matched += "screen id=$id"
                } else if (desc != null &&
                    rules.screenDescKeywords.any { desc.contains(it, ignoreCase = true) }
                ) {
                    screenHit = true
                    matched += "screen desc=\"$desc\""
                }
            }

            if (tabBounds == null && node.isClickable && !node.bounds.isEmpty &&
                node.bounds.top >= minTabTop &&
                node.bounds.height <= maxTabHeight &&
                node.bounds.width <= maxTabWidth
            ) {
                val idHit = id != null && rules.tabIdPatterns.any { it.containsMatchIn(id) }
                val label = desc ?: node.text?.toString()
                val labelHit =
                    label != null && rules.tabDescKeywords.any { label.contains(it, ignoreCase = true) }
                if (idHit || labelHit) {
                    tabBounds = node.bounds
                    matched += "tab id=$id desc=\"$label\" bounds=${node.bounds}"
                }
            }

            if (screenHit && tabBounds != null) break

            if (node.depth < rules.maxDepth) {
                val children = minOf(node.childCount, rules.maxChildrenPerNode)
                for (i in 0 until children) {
                    node.childAt(i)?.let { queue.add(it) }
                }
            }
        }

        return ScanResult(screenHit, tabBounds, matched)
    }
}
