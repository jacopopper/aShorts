package com.ashorts.blocker.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeTabNode(override val bounds: IntBounds) : NodeHandle {
    override val viewId: String? = "com.instagram.android:id/clips_tab"
    override val text: CharSequence? = null
    override val contentDescription: CharSequence? = "Reels"
    override val isClickable: Boolean = true
    override val isVisibleToUser: Boolean = true
    override val depth: Int = 4
    override val childCount: Int = 0
    override fun childAt(index: Int): NodeHandle? = null
    override fun parent(): NodeHandle? = null
}

/**
 * Replays the behaviors seen on-device as pure timelines: tab visible -> enters Reels ->
 * automatic back -> lingering tree is ignored -> still in Reels means more backs -> cover only
 * when all backs fail -> one Leave tap gets out, even through stacked Reels screens.
 *
 * Entry into Reels requires TWO consecutive detections, so transient false positives (stories
 * once tripped the single-scan rule) can never trigger anything.
 */
class ReelsGatekeeperTest {

    private val tab = FakeTabNode(IntBounds(216, 2112, 432, 2256))

    // settle = 700 ms, up to 3 backs before the cover
    private fun gate() = ReelsGatekeeper(settleMs = 700, maxBacks = 3) { it.bounds }

    private fun ReelsGatekeeper.reels(now: Long, useCover: Boolean = true) =
        onScan(reelsScan(now), now, hideTab = true, useCover = useCover)

    /** Confirmed entry (back #1 at 350), then backs #2 at 1100 and #3 at 1900, cover at 2700. */
    private fun ReelsGatekeeper.untilCovered(): List<BlockerAction> {
        reels(100); reels(350); reels(1100); reels(1900)
        return reels(2700)
    }

    private fun reelsScan(now: Long, withTab: Boolean = false) =
        DetectionEngine.ScanResult(
            reelsScreenDetected = true,
            reelsTab = if (withTab) tab else null,
            matched = listOf("screen id=com.instagram.android:id/clips_viewer_debug_container")
        )

    private fun cleanScan(withTab: Boolean) =
        DetectionEngine.ScanResult(
            reelsScreenDetected = false,
            reelsTab = if (withTab) tab else null,
            matched = if (withTab) listOf("tab id=com.instagram.android:id/clips_tab desc=\"Reels\"") else emptyList()
        )

    @Test
    fun `tab on feed shows patch without cover`() {
        val g = gate()
        val actions = g.onScan(cleanScan(withTab = true), now = 0, hideTab = true, useCover = true)
        assertTrue(actions.any { it is BlockerAction.ShowPatch })
        assertTrue(actions.none { it is BlockerAction.ShowCover })
        assertTrue(actions.none { it is BlockerAction.PressBack })
    }

    @Test
    fun `a single reels detection never presses back`() {
        val g = gate()
        g.onScan(cleanScan(withTab = true), now = 0, hideTab = true, useCover = true)
        val actions = g.onScan(reelsScan(now = 100), now = 100, hideTab = true, useCover = true)
        assertTrue(actions.none { it is BlockerAction.PressBack })
        assertTrue(actions.none { it is BlockerAction.ShowCover })
    }

    @Test
    fun `a clean scan between pending detections cancels the entry`() {
        val g = gate()
        g.onScan(reelsScan(now = 100), now = 100, hideTab = true, useCover = true)
        g.onScan(cleanScan(withTab = true), now = 200, hideTab = true, useCover = true)
        val next = g.onScan(reelsScan(now = 300), now = 300, hideTab = true, useCover = true)
        assertTrue(next.none { it is BlockerAction.PressBack }) // still just the first detection

        val confirmed = g.onScan(reelsScan(now = 500), now = 500, hideTab = true, useCover = true)
        assertEquals(1, confirmed.count { it is BlockerAction.PressBack })
    }

    @Test
    fun `second consecutive reels scan presses back exactly once and hides patch`() {
        val g = gate()
        g.onScan(cleanScan(withTab = true), now = 0, hideTab = true, useCover = true)
        g.onScan(reelsScan(now = 100), now = 100, hideTab = true, useCover = true)
        val actions = g.onScan(reelsScan(now = 350), now = 350, hideTab = true, useCover = true)
        assertEquals(1, actions.count { it is BlockerAction.PressBack })
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
        assertEquals(1, second.count { it is BlockerAction.PressBack })
        assertTrue(second.none { it is BlockerAction.ShowCover })
        val third = g.reels(1900)
        assertEquals(1, third.count { it is BlockerAction.PressBack })
        assertTrue(third.none { it is BlockerAction.ShowCover })
    }

    @Test
    fun `cover appears only after every back failed`() {
        val g = gate()
        val actions = g.untilCovered()
        assertTrue(actions.any { it is BlockerAction.ShowCover })
        assertTrue(actions.none { it is BlockerAction.PressBack })
    }

    @Test
    fun `while covered no more backs are pressed`() {
        val g = gate()
        g.untilCovered()
        val later = g.reels(5000)
        assertTrue(later.none { it is BlockerAction.PressBack })
    }

    @Test
    fun `manual leave hides cover and presses back`() {
        val g = gate()
        g.untilCovered()
        val actions = g.onManualLeave(now = 3000)
        assertTrue(actions.any { it is BlockerAction.HideCover })
        assertEquals(1, actions.count { it is BlockerAction.PressBack })
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
        assertEquals(1, nextReelsScreen.count { it is BlockerAction.PressBack })
        assertTrue(nextReelsScreen.none { it is BlockerAction.ShowCover })

        val out = g.onScan(cleanScan(withTab = true), now = 4000, hideTab = true, useCover = true)
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
        val actions = g.reels(5400)
        assertTrue(actions.any { it is BlockerAction.ShowCover })
    }

    @Test
    fun `target gone resets state so next reels entry presses back again`() {
        val g = gate()
        g.untilCovered()
        val gone = g.onTargetGone(now = 2800)
        assertTrue(gone.any { it is BlockerAction.HideCover })
        assertTrue(gone.any { it is BlockerAction.HidePatch })

        val first = g.onScan(reelsScan(now = 3000), now = 3000, hideTab = true, useCover = true)
        val second = g.onScan(reelsScan(now = 3200), now = 3200, hideTab = true, useCover = true)
        assertTrue(first.none { it is BlockerAction.PressBack }) // reset: needs confirmation again
        assertEquals(1, second.count { it is BlockerAction.PressBack })
    }

    @Test
    fun `cover disabled never shows the cover and stops pressing back`() {
        val g = gate()
        listOf(100L, 350L, 1100L, 1900L, 2700L).forEach { g.reels(it, useCover = false) }
        val actions = g.reels(5000, useCover = false)
        assertTrue(actions.none { it is BlockerAction.ShowCover || it is BlockerAction.PressBack })
    }

    @Test
    fun `tab suppression disabled never shows the patch`() {
        val g = gate()
        val actions = g.onScan(cleanScan(withTab = true), now = 0, hideTab = false, useCover = true)
        assertTrue(actions.none { it is BlockerAction.ShowPatch })
        assertTrue(actions.any { it is BlockerAction.HidePatch })
    }
}
