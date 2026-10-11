package com.ashorts.blocker.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeNode(
    override val viewId: String? = null,
    override val text: CharSequence? = null,
    override val contentDescription: CharSequence? = null,
    override val isClickable: Boolean = false,
    override val bounds: IntBounds = IntBounds(0, 0, 100, 100),
    override val depth: Int = 0,
    private val children: List<NodeHandle> = emptyList()
) : NodeHandle {
    var parentNode: NodeHandle? = null
    override val childCount: Int get() = children.size
    override fun childAt(index: Int): NodeHandle? = children.getOrNull(index)
    override fun parent(): NodeHandle? = parentNode
}

class DetectionEngineTest {

    private val screenWidth = 1080
    private val screenHeight = 2400
    private val rules = InstagramRules.rules(aggressiveTextMatch = false)

    // A 1080x2400 screen, bottom bar from y=2280, five tabs of 216px each.
    private val homeTab = FakeNode(
        viewId = "com.instagram.android:id/tab_icon_home",
        isClickable = true,
        contentDescription = "Home",
        bounds = IntBounds(0, 2280, 216, 2400),
        depth = 3
    )
    private val reelsTab = FakeNode(
        viewId = "com.instagram.android:id/tab_icon_reel",
        isClickable = true,
        contentDescription = "Reels",
        bounds = IntBounds(432, 2280, 648, 2400),
        depth = 3
    )

    @Test
    fun detectsReelsScreenByViewId() {
        val root = FakeNode(
            viewId = "com.instagram.android:id/main_tab_container",
            children = listOf(
                FakeNode(viewId = "com.instagram.android:id/reel_viewer_root", depth = 1)
            )
        )
        val result = DetectionEngine(rules).scan(root, screenWidth, screenHeight)
        assertTrue(result.reelsScreenDetected)
        assertTrue(result.matched.any { it.contains("reel_viewer_root") })
    }

    @Test
    fun detectsReelsScreenByClipsId() {
        val root = FakeNode(
            children = listOf(
                FakeNode(viewId = "com.instagram.android:id/clips_pager_fragment", depth = 1)
            )
        )
        val result = DetectionEngine(rules).scan(root, screenWidth, screenHeight)
        assertTrue(result.reelsScreenDetected)
    }

    @Test
    fun detectsReelsTabByDescriptionInBottomBar() {
        val root = FakeNode(
            children = listOf(
                FakeNode(
                    viewId = "com.instagram.android:id/bottom_tab_bar",
                    depth = 1,
                    children = listOf(homeTab, reelsTab)
                )
            )
        )
        val result = DetectionEngine(rules).scan(root, screenWidth, screenHeight)
        assertFalse(result.reelsScreenDetected)
        assertNotNull(result.reelsTab)
        assertEquals(reelsTab.bounds, result.reelsTab?.bounds)
    }

    @Test
    fun detectsReelsTabByIdPatternWithoutDescription() {
        val tab = FakeNode(
            viewId = "com.instagram.android:id/reel_tab",
            isClickable = true,
            bounds = IntBounds(432, 2280, 648, 2400),
            depth = 1
        )
        val root = FakeNode(children = listOf(tab))
        val result = DetectionEngine(rules).scan(root, screenWidth, screenHeight)
        assertNotNull(result.reelsTab)
        assertEquals(tab.bounds, result.reelsTab?.bounds)
    }

    @Test
    fun ignoresReelsDescriptionOutsideBottomBar() {
        val label = FakeNode(
            contentDescription = "Reels",
            bounds = IntBounds(100, 100, 400, 220), // top of the screen, not the nav bar
            depth = 1
        )
        val root = FakeNode(children = listOf(label))
        val result = DetectionEngine(rules).scan(root, screenWidth, screenHeight)
        assertNull(result.reelsTab)
        assertFalse(result.reelsScreenDetected)
    }

    @Test
    fun ignoresFullWidthClickableRows() {
        // A wide clickable row is not a tab button.
        val row = FakeNode(
            isClickable = true,
            contentDescription = "Reels",
            bounds = IntBounds(0, 2280, 1080, 2400),
            depth = 1
        )
        val root = FakeNode(children = listOf(row))
        val result = DetectionEngine(rules).scan(root, screenWidth, screenHeight)
        assertNull(result.reelsTab)
    }

    @Test
    fun noFalsePositiveOnRegularFeedIds() {
        val root = FakeNode(
            viewId = "com.instagram.android:id/feed_container",
            children = listOf(
                FakeNode(viewId = "com.instagram.android:id/media_container", depth = 1),
                FakeNode(viewId = "com.instagram.android:id/action_bar_row", depth = 1),
                homeTab
            )
        )
        val result = DetectionEngine(rules).scan(root, screenWidth, screenHeight)
        assertFalse(result.reelsScreenDetected)
        assertNull(result.reelsTab)
        assertTrue(result.matched.isEmpty())
    }

    @Test
    fun aggressiveTextMatchFlagsReelsDescriptionAsScreen() {
        val root = FakeNode(
            children = listOf(
                FakeNode(contentDescription = "Reels and more", bounds = IntBounds(0, 0, 500, 200), depth = 1)
            )
        )
        val lenient = DetectionEngine(InstagramRules.rules(aggressiveTextMatch = false))
        assertFalse(lenient.scan(root, screenWidth, screenHeight).reelsScreenDetected)
        val aggressive = DetectionEngine(InstagramRules.rules(aggressiveTextMatch = true))
        assertTrue(aggressive.scan(root, screenWidth, screenHeight).reelsScreenDetected)
    }

    @Test
    fun depthCapStopsTraversal() {
        val root = FakeNode(
            children = listOf(
                FakeNode(viewId = "com.instagram.android:id/reel_viewer_root", depth = 1)
            )
        )
        val result = DetectionEngine(rules.copy(maxDepth = 0))
            .scan(root, screenWidth, screenHeight)
        assertFalse(result.reelsScreenDetected)
    }

    @Test
    fun nodeCapStopsTraversal() {
        val root = FakeNode(
            children = listOf(
                FakeNode(viewId = "com.instagram.android:id/reel_viewer_root", depth = 1)
            )
        )
        val result = DetectionEngine(rules.copy(maxNodes = 1))
            .scan(root, screenWidth, screenHeight)
        assertFalse(result.reelsScreenDetected)
    }

    @Test
    fun stopsEarlyWhenEverythingIsFound() {
        val deepReelId = FakeNode(viewId = "com.instagram.android:id/reel_viewer_root", depth = 2)
        val root = FakeNode(
            children = listOf(
                FakeNode(
                    viewId = "com.instagram.android:id/bottom_tab_bar",
                    depth = 1,
                    children = listOf(reelsTab)
                ),
                FakeNode(children = listOf(deepReelId), depth = 1)
            )
        )
        val result = DetectionEngine(rules.copy(maxNodes = 5))
            .scan(root, screenWidth, screenHeight)
        assertTrue(result.reelsScreenDetected)
        assertNotNull(result.reelsTab)
        // With maxNodes=5 (root, tab bar, reels tab, second branch, deepReelId) everything fits.
    }

    // ----------------------------------------------------------- patch bounds expansion

    @Test
    fun expandedTabBoundsCoverTheFullCellAndBarHeight() {
        // The matched node is just the icon; the visible button is the cell inside the bar.
        val icon = FakeNode(
            viewId = "com.instagram.android:id/reel_tab_icon",
            isClickable = true,
            contentDescription = "Reels",
            bounds = IntBounds(528, 2330, 560, 2360)
        )
        val cell = FakeNode(viewId = "com.instagram.android:id/tab_cell", bounds = IntBounds(432, 2300, 648, 2392))
        val bar = FakeNode(viewId = "com.instagram.android:id/bottom_bar", bounds = IntBounds(0, 2280, 1080, 2400))
        icon.parentNode = cell
        cell.parentNode = bar

        val patch = expandedTabBounds(icon, screenWidth, screenHeight)
        // Full bar height (down to the screen bottom), full cell width.
        assertEquals(IntBounds(432, 2280, 648, 2400), patch)
    }

    @Test
    fun expandedTabBoundsNeverExpandAboveTheBottomRegion() {
        val icon = FakeNode(
            contentDescription = "Reels",
            isClickable = true,
            bounds = IntBounds(528, 2330, 560, 2360)
        )
        val bar = FakeNode(viewId = "com.instagram.android:id/bottom_bar", bounds = IntBounds(0, 2280, 1080, 2400))
        val fullScreen = FakeNode(viewId = "com.instagram.android:id/root", bounds = IntBounds(0, 0, 1080, 2400))
        icon.parentNode = bar
        bar.parentNode = fullScreen

        val patch = expandedTabBounds(icon, screenWidth, screenHeight)
        // The full-screen ancestor (top=0) must stop the walk: top stays at the bar's top and
        // its full width must not leak into the patch.
        assertEquals(IntBounds(528, 2280, 560, 2400), patch)
    }

    @Test
    fun expandedTabBoundsSkipDegenerateAncestors() {
        val icon = FakeNode(
            contentDescription = "Reels",
            isClickable = true,
            bounds = IntBounds(528, 2330, 560, 2360)
        )
        val broken = FakeNode(viewId = "com.instagram.android:id/broken", bounds = IntBounds(0, 0, 0, 0))
        val cell = FakeNode(viewId = "com.instagram.android:id/tab_cell", bounds = IntBounds(432, 2300, 648, 2392))
        val bar = FakeNode(viewId = "com.instagram.android:id/bottom_bar", bounds = IntBounds(0, 2280, 1080, 2400))
        icon.parentNode = broken
        broken.parentNode = cell
        cell.parentNode = bar

        val patch = expandedTabBounds(icon, screenWidth, screenHeight)
        // The empty-bounds ancestor is skipped, the cell widens us, the bar gives the height.
        assertEquals(IntBounds(432, 2280, 648, 2400), patch)
    }
}
