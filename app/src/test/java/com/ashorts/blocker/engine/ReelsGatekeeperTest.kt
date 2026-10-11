package com.ashorts.blocker.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeTabNode(
    override val bounds: IntBounds,
    override val viewId: String? = "com.instagram.android:id/clips_tab",
    override val contentDescription: CharSequence? = "Reels",
    override val isSelected: Boolean = false
) : NodeHandle {
    override val text: CharSequence? = null
    override val isClickable: Boolean = true
    override val isVisibleToUser: Boolean = true
    override val depth: Int = 4
    override val childCount: Int = 0
    override fun childAt(index: Int): NodeHandle? = null
    override fun parent(): NodeHandle? = null
}

/**
 * Replays the behaviors seen on-device as pure timelines: tab visible -> enters Reels ->
 * automatic exit -> lingering tree is ignored -> still in Reels means more exits -> cover only
 * when all of them fail -> one Leave tap gets out, even through stacked Reels screens.
 *
 * Entry into Reels requires TWO consecutive detections, so transient false positives (stories
 * once tripped the single-scan rule) can never trigger anything.
 */
class ReelsGatekeeperTest {

    private val tab = FakeTabNode(IntBounds(216, 2112, 432, 2256))
    private val selectedTab = FakeTabNode(IntBounds(216, 2112, 432, 2256), isSelected = true)
    private val home = FakeTabNode(
        IntBounds(0, 2112, 216, 2256),
        viewId = "com.instagram.android:id/feed_tab",
        contentDescription = "Home"
    )
    private val selectedHome = FakeTabNode(
        IntBounds(0, 2112, 216, 2256),
        viewId = "com.instagram.android:id/feed_tab",
        contentDescription = "Home",
        isSelected = true
    )

    // settle = 700 ms, up to 3 backs before the cover
    private fun gate() = ReelsGatekeeper(settleMs = 700, maxBacks = 3) { it.bounds }

    private fun ReelsGatekeeper.reels(now: Long) = onScan(reelsScan(), now)

    /** Confirmed entry (back #1 at 350), then backs #2 at 1100 and #3 at 1900, cover at 2700. */
    private fun ReelsGatekeeper.untilCovered(): List<BlockerAction> {
        reels(100); reels(350); reels(1100); reels(1900)
        return reels(2700)
    }

    private fun reelsScan(
        reelsTab: NodeHandle? = null,
        homeTab: NodeHandle? = null,
        visible: Boolean = true
    ) = DetectionEngine.ScanResult(
        reelsScreenDetected = true,
        reelsTab = reelsTab,
        matched = listOf("screen id=com.instagram.android:id/clips_viewer_debug_container"),
        reelsScreenVisible = visible,
        homeTab = homeTab
    )

    private fun cleanScan(withTab: Boolean) =
        DetectionEngine.ScanResult(
            reelsScreenDetected = false,
            reelsTab = if (withTab) tab else null,
            matched = if (withTab) listOf("tab id=com.instagram.android:id/clips_tab desc=\"Reels\"") else emptyList(),
            homeTab = if (withTab) selectedHome else null
        )

    private fun List<BlockerAction>.backs() = count { it is BlockerAction.PressBack }
    private fun List<BlockerAction>.homeTaps() = count { it is BlockerAction.TapHome }

    @Test
    fun `tab on feed shows patch without cover`() {
        val g = gate()
        val actions = g.onScan(cleanScan(withTab = true), now = 0)
        assertTrue(actions.any { it is BlockerAction.ShowPatch })
        assertTrue(actions.none { it is BlockerAction.ShowCover })
        assertEquals(0, actions.backs())
    }

    @Test
    fun `no tab on screen hides the patch`() {
        val actions = gate().onScan(cleanScan(withTab = false), now = 0)
        assertTrue(actions.any { it is BlockerAction.HidePatch })
        assertTrue(actions.none { it is BlockerAction.ShowPatch })
    }

    @Test
    fun `a single reels detection never presses back`() {
        val g = gate()
        g.onScan(cleanScan(withTab = true), now = 0)
        val actions = g.reels(100)
        assertEquals(0, actions.backs())
        assertTrue(actions.none { it is BlockerAction.ShowCover })
    }

    @Test
    fun `a clean scan between pending detections cancels the entry`() {
        val g = gate()
        g.reels(100)
        g.onScan(cleanScan(withTab = true), now = 200)
        val next = g.reels(300)
        assertEquals(0, next.backs()) // still just the first detection

        val confirmed = g.reels(500)
        assertEquals(1, confirmed.backs())
    }

    @Test
    fun `second consecutive reels scan presses back exactly once and hides patch`() {
        val g = gate()
        g.onScan(cleanScan(withTab = true), now = 0)
        g.reels(100)
        val actions = g.reels(350)
        assertEquals(1, actions.backs())
        assertTrue(actions.any { it is BlockerAction.HidePatch })
        assertTrue(actions.none { it is BlockerAction.ShowCover })
    }

    @Test
    fun `detections while the back transition settles do nothing`() {
        val g = gate()
        g.reels(100); g.reels(350) // back #1
        val lingering = g.reels(900)
        assertTrue(lingering.none { it is BlockerAction.PressBack || it is BlockerAction.ShowCover })
    }

    /** Stacked Reels screens (shared link, DMs): one Back is not enough, so keep going. */
    @Test
    fun `still in reels after settling presses back again before any cover`() {
        val g = gate()
        g.reels(100); g.reels(350) // back #1
        val second = g.reels(1100)
        assertEquals(1, second.backs())
        assertTrue(second.none { it is BlockerAction.ShowCover })
        val third = g.reels(1900)
        assertEquals(1, third.backs())
        assertTrue(third.none { it is BlockerAction.ShowCover })
    }

    @Test
    fun `cover appears only after every back failed`() {
        val g = gate()
        val actions = g.untilCovered()
        assertTrue(actions.any { it is BlockerAction.ShowCover })
        assertEquals(0, actions.backs())
    }

    @Test
    fun `while covered no more backs are pressed`() {
        val g = gate()
        g.untilCovered()
        assertEquals(0, g.reels(5000).backs())
    }

    @Test
    fun `manual leave hides cover and presses back`() {
        val g = gate()
        g.untilCovered()
        val actions = g.onManualLeave(now = 3000)
        assertTrue(actions.any { it is BlockerAction.HideCover })
        assertEquals(1, actions.backs())
    }

    /**
     * Regression for "press Leave twice": after Leave, the next Reels screen in the back stack
     * must be handled with another automatic Back, never by bringing the cover back.
     */
    @Test
    fun `one leave tap gets out through stacked reels without the cover returning`() {
        val g = gate()
        g.untilCovered()
        g.onManualLeave(now = 3000)

        val lingering = g.reels(3200)
        assertTrue(lingering.none { it is BlockerAction.ShowCover || it is BlockerAction.PressBack })

        val nextReelsScreen = g.reels(3800)
        assertEquals(1, nextReelsScreen.backs())
        assertTrue(nextReelsScreen.none { it is BlockerAction.ShowCover })

        val out = g.onScan(cleanScan(withTab = true), now = 4000)
        assertTrue(out.none { it is BlockerAction.ShowCover })
        assertTrue(out.any { it is BlockerAction.HideCover })
    }

    @Test
    fun `after leave the cover returns only when every back failed again`() {
        val g = gate()
        g.untilCovered()
        g.onManualLeave(now = 3000) // back #1
        g.reels(3800) // back #2
        g.reels(4600) // back #3
        assertTrue(g.reels(5400).any { it is BlockerAction.ShowCover })
    }

    @Test
    fun `target gone resets state so next reels entry presses back again`() {
        val g = gate()
        g.untilCovered()
        val gone = g.onTargetGone(now = 2800)
        assertTrue(gone.any { it is BlockerAction.HideCover })
        assertTrue(gone.any { it is BlockerAction.HidePatch })

        val first = g.reels(3000)
        val second = g.reels(3200)
        assertEquals(0, first.backs()) // reset: needs confirmation again
        assertEquals(1, second.backs())
    }

    // ------------------------------------------------------------- Reels opened from its tab

    /** The "tap again to exit" bug: Reels is a root tab, so extra Backs close Instagram. */
    @Test
    fun `reels opened from its own tab switches to Home and never presses back`() {
        val g = gate()
        g.onScan(reelsScan(selectedTab, home), now = 100)
        val entry = g.onScan(reelsScan(selectedTab, home), now = 350)
        assertEquals(1, entry.homeTaps())
        assertEquals(0, entry.backs())

        val lingering = g.onScan(reelsScan(selectedTab, home), now = 900)
        assertEquals(0, lingering.homeTaps() + lingering.backs())

        val again = g.onScan(reelsScan(selectedTab, home), now = 1100)
        assertEquals(1, again.homeTaps())
        assertEquals(0, again.backs())

        val stuck = g.onScan(reelsScan(selectedTab, home), now = 1900)
        assertTrue(stuck.any { it is BlockerAction.ShowCover })
        assertEquals(0, stuck.backs())
    }

    @Test
    fun `a tap on the reels tab switches to Home immediately`() {
        val g = gate()
        g.onScan(cleanScan(withTab = true), now = 0)
        val actions = g.onReelsTabTapped(now = 50, homeTab = home)
        assertEquals(1, actions.homeTaps())
        assertEquals(0, actions.backs())

        // Home worked: the next clean scan simply puts the patch back.
        val out = g.onScan(cleanScan(withTab = true), now = 400)
        assertTrue(out.any { it is BlockerAction.ShowPatch })
        assertEquals(0, out.backs())
    }

    @Test
    fun `after a tab tap a single reels detection is enough to act`() {
        val g = gate()
        g.onReelsTabTapped(now = 50, homeTab = null) // Home tab not found at tap time
        val actions = g.onScan(reelsScan(tab, home), now = 300)
        assertEquals(1, actions.homeTaps())
        assertEquals(0, actions.backs())
    }

    @Test
    fun `without a Home tab a tab-opened reels gets exactly one back`() {
        val g = gate()
        g.onScan(reelsScan(selectedTab), now = 100)
        val entry = g.onScan(reelsScan(selectedTab), now = 350)
        assertEquals(1, entry.backs())
        val later = g.onScan(reelsScan(selectedTab), now = 1100)
        assertEquals(0, later.backs())
        assertTrue(later.any { it is BlockerAction.ShowCover })
    }

    @Test
    fun `reels opened from a profile with the bar visible backs out once then taps Home`() {
        val g = gate()
        g.onScan(reelsScan(tab, home), now = 100)
        val entry = g.onScan(reelsScan(tab, home), now = 350)
        assertEquals(1, entry.backs())
        val second = g.onScan(reelsScan(tab, home), now = 1100)
        assertEquals(1, second.homeTaps())
        assertEquals(0, second.backs())
        val stuck = g.onScan(reelsScan(tab, home), now = 1900)
        assertTrue(stuck.any { it is BlockerAction.ShowCover })
    }

    @Test
    fun `while Home is the selected tab back is never pressed`() {
        val g = gate()
        g.onScan(reelsScan(tab, selectedHome), now = 100)
        val entry = g.onScan(reelsScan(tab, selectedHome), now = 350)
        assertEquals(1, entry.homeTaps())
        assertEquals(0, entry.backs())
    }

    @Test
    fun `a hidden reels screen behind the bar never brings the cover`() {
        val g = gate()
        g.onScan(reelsScan(selectedTab, home), now = 100)
        g.onScan(reelsScan(selectedTab, home), now = 350) // Home #1
        g.onScan(reelsScan(tab, selectedHome, visible = false), now = 1100) // Home #2
        val after = g.onScan(reelsScan(tab, selectedHome, visible = false), now = 1900)
        assertTrue(after.none { it is BlockerAction.ShowCover })
        assertTrue(after.any { it is BlockerAction.ShowPatch })
        assertEquals(0, after.backs())
    }

    @Test
    fun `leave after a tab-opened reels taps Home again instead of back`() {
        val g = gate()
        g.onScan(reelsScan(selectedTab, home), now = 100)
        g.onScan(reelsScan(selectedTab, home), now = 350)
        g.onScan(reelsScan(selectedTab, home), now = 1100)
        g.onScan(reelsScan(selectedTab, home), now = 1900) // covered
        val leave = g.onManualLeave(now = 2500)
        assertEquals(1, leave.homeTaps())
        assertEquals(0, leave.backs())
    }
}
