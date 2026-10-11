package com.ashorts.blocker.engine

import kotlin.math.max
import kotlin.math.min

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
        /** The matched Reels tab node (null if not present). Use [expandedTabBounds] for patch bounds. */
        val reelsTab: NodeHandle?,
        /** Human-readable list of what matched, used by the detection log. */
        val matched: List<String>
    )

    fun scan(root: NodeHandle, screenWidth: Int, screenHeight: Int): ScanResult {
        val matched = mutableListOf<String>()
        var screenHit = false
        var tabNode: NodeHandle? = null

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

            if (tabNode == null && node.isClickable && !node.bounds.isEmpty &&
                node.bounds.top >= minTabTop &&
                node.bounds.height <= maxTabHeight &&
                node.bounds.width <= maxTabWidth
            ) {
                val idHit = id != null && rules.tabIdPatterns.any { it.containsMatchIn(id) }
                val label = desc ?: node.text?.toString()
                val labelHit =
                    label != null && rules.tabDescKeywords.any { label.contains(it, ignoreCase = true) }
                if (idHit || labelHit) {
                    tabNode = node
                    matched += "tab id=$id desc=\"$label\" bounds=${node.bounds}"
                }
            }

            if (screenHit && tabNode != null) break

            if (node.depth < rules.maxDepth) {
                val children = minOf(node.childCount, rules.maxChildrenPerNode)
                for (i in 0 until children) {
                    node.childAt(i)?.let { queue.add(it) }
                }
            }
        }

        return ScanResult(screenHit, tabNode, matched)
    }
}

/**
 * Turns a matched tab node into patch bounds that cover the whole visible button.
 *
 * The node we match may be smaller than what is actually drawn on screen (an icon inside a cell,
 * a cell above the gesture-nav strip, a label under the icon...). So we walk up the parent chain:
 *
 *  - vertically: from the top of the tallest enclosing ancestor that is still inside the bottom
 *    region, down to the very bottom of the screen (covering the gesture-nav strip as well);
 *  - horizontally: to the widest ancestor narrower than the full bar, so the patch covers the
 *    entire tappable cell without eating the neighbouring tabs.
 */
fun expandedTabBounds(tab: NodeHandle, screenWidth: Int, screenHeight: Int): IntBounds {
    val regionTop = (screenHeight * 0.60f).toInt()
    var left = tab.bounds.left
    var right = tab.bounds.right
    var top = tab.bounds.top

    var current = tab.parent()
    while (current != null) {
        val b = current.bounds
        if (b.isEmpty) {
            current = current.parent()
            continue
        }
        if (b.top < regionTop) break
        top = min(top, b.top)
        if (b.width < screenWidth * 0.80f) {
            left = min(left, b.left)
            right = max(right, b.right)
        }
        current = current.parent()
    }

    left = left.coerceIn(0, screenWidth - 2)
    right = right.coerceIn(left + 1, screenWidth)
    top = top.coerceIn(regionTop, screenHeight - 2)
    return IntBounds(left, top, right, screenHeight)
}
