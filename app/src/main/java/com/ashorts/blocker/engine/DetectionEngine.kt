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
 *
 * Notes:
 *  - The Reels screen is matched only via `clips_` ids (Instagram's reels namespace); `reel_`
 *    belongs to stories and must never trigger blocking.
 *  - A tab candidate must be actually visible to the user: Instagram keeps tab-bar nodes in the
 *    tree behind fullscreen overlays (stories, reels), and a patch over such a node would sit
 *    on top of the overlay's content.
 */
class DetectionEngine(private val rules: AppBlockRules) {

    data class ScanResult(
        /** True when the full-screen Reels feed itself is in the window tree. */
        val reelsScreenDetected: Boolean,
        /** The matched Reels tab node (null if not present). Use [expandedTabBounds] for patch bounds. */
        val reelsTab: NodeHandle?,
        /** Human-readable list of what matched, used by the detection log. */
        val matched: List<String>,
        /**
         * False when every matched Reels node is in the tree but hidden: a Reels screen the app
         * keeps loaded behind another tab.
         */
        val reelsScreenVisible: Boolean = reelsScreenDetected,
        /** The bottom-bar Home tab, used to leave a Reels screen that has no screen behind it. */
        val homeTab: NodeHandle? = null
    )

    /** True for a node that sits in the bottom bar and looks like the Reels tab. */
    fun isReelsTab(node: NodeHandle, screenWidth: Int, screenHeight: Int): Boolean =
        isBarTab(node, screenWidth, screenHeight) &&
            matches(node, rules.tabIdPatterns, rules.tabDescKeywords)

    private fun isBarTab(node: NodeHandle, screenWidth: Int, screenHeight: Int): Boolean =
        node.isClickable && node.isVisibleToUser &&
            !node.bounds.isEmpty &&
            node.bounds.top >= (screenHeight * rules.tabRegionTopFraction).toInt() &&
            node.bounds.height <= (screenHeight * rules.tabMaxHeightFraction).toInt() &&
            node.bounds.width <= (screenWidth * rules.tabMaxWidthFraction).toInt()

    private fun matches(node: NodeHandle, ids: List<Regex>, keywords: List<String>): Boolean {
        val id = node.viewId
        if (id != null && ids.any { it.containsMatchIn(id) }) return true
        val label = node.contentDescription?.toString() ?: node.text?.toString() ?: return false
        return keywords.any { label.contains(it, ignoreCase = true) }
    }

    fun scan(root: NodeHandle, screenWidth: Int, screenHeight: Int): ScanResult {
        val matched = mutableListOf<String>()
        var screenHit = false
        var screenVisible = false
        var tabNode: NodeHandle? = null
        var homeNode: NodeHandle? = null

        val queue = ArrayDeque<NodeHandle>()
        queue.add(root)
        var visited = 0

        while (queue.isNotEmpty() && visited < rules.maxNodes) {
            val node = queue.removeFirst()
            visited++

            val id = node.viewId
            val desc = node.contentDescription?.toString()

            if (!screenVisible) {
                val hit = when {
                    id != null && rules.screenIdPatterns.any { it.containsMatchIn(id) } -> "id=$id"
                    desc != null &&
                        rules.screenDescKeywords.any { desc.contains(it, ignoreCase = true) } ->
                        "desc=\"$desc\""
                    else -> null
                }
                if (hit != null) {
                    val visible = node.isVisibleToUser
                    if (!screenHit || visible) matched += "screen $hit visible=$visible"
                    screenHit = true
                    screenVisible = visible
                }
            }

            if ((tabNode == null || homeNode == null) && isBarTab(node, screenWidth, screenHeight)) {
                val label = desc ?: node.text?.toString()
                if (tabNode == null && matches(node, rules.tabIdPatterns, rules.tabDescKeywords)) {
                    tabNode = node
                    matched += "tab id=$id desc=\"$label\" bounds=${node.bounds} selected=${node.isSelected}"
                } else if (homeNode == null &&
                    matches(node, rules.homeTabIdPatterns, rules.homeTabDescKeywords)
                ) {
                    homeNode = node
                }
            }

            if (screenVisible && tabNode != null && homeNode != null) break

            if (node.depth < rules.maxDepth) {
                val children = minOf(node.childCount, rules.maxChildrenPerNode)
                for (i in 0 until children) {
                    node.childAt(i)?.let { queue.add(it) }
                }
            }
        }

        if (screenHit) {
            homeNode?.let {
                matched += "home id=${it.viewId} desc=\"${it.contentDescription ?: it.text}\" " +
                    "selected=${it.isSelected}"
            }
        }
        return ScanResult(screenHit, tabNode, matched, screenVisible, homeNode)
    }
}

/**
 * Turns a matched tab node into patch bounds that cover the whole visible button.
 *
 * The node we match may be smaller than what is actually drawn on screen (an icon inside a cell,
 * a label under the icon...). So we walk up the parent chain:
 *
 *  - top: the top of the highest enclosing ancestor still inside the bottom region (the bar);
 *  - bottom and sides: only from cell-sized ancestors (narrower than the full bar). In
 *    edge-to-edge layouts the full-width bar container extends down *behind the system
 *    navigation bar*; taking its bottom made the patch cover the recents button. [maxBottom]
 *    (the top of the system nav bar) clamps as a second safety net;
 *  - horizontally the widest such cell wins, so the patch covers the entire tappable cell
 *    without eating the neighbouring tabs.
 */
fun expandedTabBounds(
    tab: NodeHandle,
    screenWidth: Int,
    screenHeight: Int,
    maxBottom: Int = screenHeight
): IntBounds {
    val regionTop = (screenHeight * 0.60f).toInt()
    var left = tab.bounds.left
    var right = tab.bounds.right
    var top = tab.bounds.top
    var bottom = tab.bounds.bottom

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
            bottom = max(bottom, b.bottom)
        }
        current = current.parent()
    }

    left = left.coerceIn(0, screenWidth - 2)
    right = right.coerceIn(left + 1, screenWidth)
    top = top.coerceIn(regionTop, screenHeight - 2)
    val hardBottom = min(screenHeight, maxBottom)
    bottom = bottom.coerceAtMost(hardBottom)
    if (bottom <= top + 1) bottom = min(top + 2, hardBottom)
    return IntBounds(left, top, right, bottom)
}
