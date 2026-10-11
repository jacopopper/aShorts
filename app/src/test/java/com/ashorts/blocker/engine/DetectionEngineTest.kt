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
    override val isVisibleToUser: Boolean = true,
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
        viewId = "com.instagram.android:id/clips_tab",
        isClickable = true,
        contentDescription = "Reels",
        bounds = IntBounds(432, 2280, 648, 2400),
        depth = 3
    )

    @Test
    fun detectsReelsScreenByClipsId() {
        val root = FakeNode(
            viewId = "com.instagram.android:id/main_tab_container",
            children = listOf(
                FakeNode(viewId = "com.instagram.android:id/clips_viewer_debug_container", depth = 1)
            )
        )
        val result = DetectionEngine(rules).scan(root, screenWidth, screenHeight)
        assertTrue(result.reelsScreenDetected)
        assertTrue(result.matched.any { it.contains("clips_viewer_debug_container") })
    }

    @Test
    fun detectsReelsScreenByOtherClipsIds() {
        val root = FakeNode(
            children = listOf(
                FakeNode(viewId = "com.instagram.android:id/clips_pager_fragment", depth = 1)
            )
        )
        val result = DetectionEngine(rules).scan(root, screenWidth, screenHeight)
        assertTrue(result.reelsScreenDetected)
    }

    /**
     * Regression: Instagram's STORY viewer lives in the reel_ namespace (ReelViewerFragment).
     * These ids once matched and made aShorts press Back whenever a story opened, breaking
     * continuous story browsing. They must never be detected as the Reels feed.
     */
    @Test
    fun neverMatchesTheStoryViewerNamespace() {
        val storyViewer = FakeNode(
            children = listOf(
                FakeNode(viewId = "com.instagram.android:id/reel_viewer_root", depth = 1),
                FakeNode(viewId = "com.instagram.android:id/reel_container_fragment", depth = 1),
                FakeNode(viewId = "com.instagram.android:id/reel_pager", depth = 1)
            )
        )
        val result = DetectionEngine(rules).scan(storyViewer, screenWidth, screenHeight)
        assertFalse(result.reelsScreenDetected)
        assertTrue(result.matched.isEmpty())
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
    fun detectsReelsTabByConfirmedIdPatternWithoutDescription() {
        val tab = FakeNode(
            viewId = "com.instagram.android:id/clips_tab",
            isClickable = true,
            bounds = IntBounds(432, 2280, 648, 2400),
            depth = 1
        )
        val root = FakeNode(children = listOf(tab))
        val result = DetectionEngine(rules).scan(root, screenWidth, screenHeight)
        assertNotNull(result.reelsTab)
        assertEquals(tab.bounds, result.reelsTab?.bounds)
    }

    /**
     * Instagram keeps tab-bar nodes in the tree behind fullscreen overlays; a patch over a node
     * that is not actually visible would float above the overlay's content (stories).
     */
    @Test
    fun ignoresTabNodesThatAreNotVisibleToUser() {
        val hiddenTab = FakeNode(
            viewId = "com.instagram.android:id/clips_tab",
            isClickable = true,
            contentDescription = "Reels",
            isVisibleToUser = false,
            bounds = IntBounds(432, 2280, 648, 2400),
            depth = 1
        )
        val root = FakeNode(children = listOf(hiddenTab))
        val result = DetectionEngine(rules).scan(root, screenWidth, screenHeight)
        assertNull(result.reelsTab)
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
                FakeNode(viewId = "com.instagram.android:id/clips_viewer_root", depth = 1)
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
                FakeNode(viewId = "com.instagram.android:id/clips_viewer_root", depth = 1)
            )
        )
        val result = DetectionEngine(rules.copy(maxNodes = 1))
            .scan(root, screenWidth, screenHeight)
        assertFalse(result.reelsScreenDetected)
    }

    @Test
    fun stopsEarlyWhenEverythingIsFound() {
        val deepReelsId = FakeNode(viewId = "com.instagram.android:id/clips_viewer_debug_container", depth = 2)
        val root = FakeNode(
            children = listOf(
                FakeNode(
                    viewId = "com.instagram.android:id/bottom_tab_bar",
                    depth = 1,
                    children = listOf(reelsTab)
                ),
                FakeNode(children = listOf(deepReelsId), depth = 1)
            )
        )
        val result = DetectionEngine(rules.copy(maxNodes = 5))
            .scan(root, screenWidth, screenHeight)
        assertTrue(result.reelsScreenDetected)
        assertNotNull(result.reelsTab)
        // With maxNodes=5 (root, tab bar, reels tab, second branch, deepReelsId) everything fits.
    }

    // ----------------------------------------------------------- patch bounds expansion

    @Test
    fun expandedTabBoundsCoverTheFullCellAndBarHeight() {
        // The matched node is just the icon; the visible button is the cell inside the bar.
        val icon = FakeNode(
            viewId = "com.instagram.android:id/clips_tab_icon",
            isClickable = true,
            contentDescription = "Reels",
            bounds = IntBounds(528, 2330, 560, 2360)
        )
        val cell = FakeNode(viewId = "com.instagram.android:id/tab_cell", bounds = IntBounds(432, 2300, 648, 2392))
        val bar = FakeNode(viewId = "com.instagram.android:id/bottom_bar", bounds = IntBounds(0, 2280, 1080, 2400))
        icon.parentNode = cell
        cell.parentNode = bar

        val patch = expandedTabBounds(icon, screenWidth, screenHeight)
        // From the bar's top to the cell's bottom, full cell width.
        assertEquals(IntBounds(432, 2280, 648, 2392), patch)
    }

    /**
     * Regression (real device log): edge-to-edge Instagram draws its full-width bar container
     * down behind the system navigation bar. Its bottom must not be used, or the patch covers
     * the recents button.
     */
    @Test
    fun expandedTabBoundsStopAboveTheSystemNavigationBar() {
        val tab = FakeNode(
            viewId = "com.instagram.android:id/clips_tab",
            isClickable = true,
            contentDescription = "Reels",
            bounds = IntBounds(216, 2112, 432, 2256)
        )
        val barContainer = FakeNode(
            viewId = "com.instagram.android:id/tab_bar",
            bounds = IntBounds(0, 2112, 1080, 2400) // 2256..2400 is behind the nav bar
        )
        tab.parentNode = barContainer

        assertEquals(IntBounds(216, 2112, 432, 2256), expandedTabBounds(tab, screenWidth, screenHeight))
    }

    @Test
    fun expandedTabBoundsClampToMaxBottom() {
        // Even a cell that itself reaches behind the nav bar is clamped to the nav bar's top.
        val tab = FakeNode(
            contentDescription = "Reels",
            isClickable = true,
            bounds = IntBounds(216, 2112, 432, 2400)
        )
        val patch = expandedTabBounds(tab, screenWidth, screenHeight, maxBottom = 2256)
        assertEquals(IntBounds(216, 2112, 432, 2256), patch)
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
        // neither its full width nor the full-width bar's bottom leaks into the patch.
        assertEquals(IntBounds(528, 2280, 560, 2360), patch)
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
        // The empty-bounds ancestor is skipped, the cell widens us, the bar gives the top.
        assertEquals(IntBounds(432, 2280, 648, 2392), patch)
    }
}
