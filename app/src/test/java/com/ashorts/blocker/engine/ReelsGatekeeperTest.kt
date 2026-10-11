package com.ashorts.blocker.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeTabNode(override val bounds: IntBounds) : NodeHandle {
    override val viewId: String? = "com.instagram.android:id/clips_tab"
    override val text: CharSequence? = null
    override val contentDescription: CharSequence? = "Reels"
    override val isClickable: Boolean = true
    override val depth: Int = 4
    override val childCount: Int = 0
    override fun childAt(index: Int): NodeHandle? = null
    override fun parent(): NodeHandle? = null
}

/**
 * Replays the exact behaviors seen on-device (from the user's detection log) as pure timelines:
 * tab visible -> enters Reels -> automatic back -> lingering tree -> cover -> manual leave ->
 * lingering tree must NOT re-cover -> clean scan hides the cover.
 */
class ReelsGatekeeperTest {

    private val tab = FakeTabNode(IntBounds(216, 2112, 432, 2256))
    private val autoGrace = 1200L
    private val manualGrace = 1500L

    private fun gate() =
        ReelsGatekeeper(autoLeaveGraceMs = autoGrace, manualLeaveGraceMs = manualGrace) { it.bounds }

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
    fun `entering reels presses back exactly once and hides patch`() {
        val g = gate()
        g.onScan(cleanScan(withTab = true), now = 0, hideTab = true, useCover = true)
        val actions = g.onScan(reelsScan(now = 100), now = 100, hideTab = true, useCover = true)
        assertEquals(1, actions.count { it is BlockerAction.PressBack })
        assertTrue(actions.any { it is BlockerAction.HidePatch })
        assertTrue(actions.none { it is BlockerAction.ShowCover })
    }

    @Test
    fun `reels lingering within auto grace shows no cover`() {
        val g = gate()
        g.onScan(reelsScan(now = 100), now = 100, hideTab = true, useCover = true)
        val actions = g.onScan(reelsScan(now = 600), now = 600, hideTab = true, useCover = true)
        assertTrue(actions.none { it is BlockerAction.ShowCover })
    }

    @Test
    fun `cover appears after auto grace when back failed`() {
        val g = gate()
        g.onScan(reelsScan(now = 100), now = 100, hideTab = true, useCover = true)
        val actions = g.onScan(reelsScan(now = 1400), now = 1400, hideTab = true, useCover = true)
        assertTrue(actions.any { it is BlockerAction.ShowCover })
    }

    @Test
    fun `manual leave hides cover and presses back`() {
        val g = gate()
        val actions = g.onManualLeave(now = 2000)
        assertTrue(actions.any { it is BlockerAction.HideCover })
        assertTrue(actions.any { it is BlockerAction.PressBack })
    }

    @Test
    fun `single lingering detection after manual leave does not re-cover`() {
        val g = gate()
        g.onScan(reelsScan(now = 100), now = 100, hideTab = true, useCover = true)
        g.onScan(reelsScan(now = 1400), now = 1400, hideTab = true, useCover = true) // cover up
        g.onManualLeave(now = 2000)

        // The back transition briefly leaves the reels tree visible (what used to make the
        // cover flash back instantly and require pressing Leave twice).
        val lingering = g.onScan(reelsScan(now = 2100), now = 2100, hideTab = true, useCover = true)
        assertTrue(lingering.none { it is BlockerAction.ShowCover })

        // If the tree then disappears (back worked), the cover never comes back.
        val clean = g.onScan(cleanScan(withTab = true), now = 2200, hideTab = true, useCover = true)
        assertTrue(clean.none { it is BlockerAction.ShowCover })
        assertTrue(clean.any { it is BlockerAction.HideCover })
    }

    @Test
    fun `two detections after manual leave mean back failed and cover returns`() {
        val g = gate()
        g.onScan(reelsScan(now = 100), now = 100, hideTab = true, useCover = true)
        g.onManualLeave(now = 2000)
        val first = g.onScan(reelsScan(now = 2100), now = 2100, hideTab = true, useCover = true)
        val second = g.onScan(reelsScan(now = 2400), now = 2400, hideTab = true, useCover = true)
        assertTrue(first.none { it is BlockerAction.ShowCover })
        assertTrue(second.any { it is BlockerAction.ShowCover })
    }

    @Test
    fun `target gone resets state so next reels entry presses back again`() {
        val g = gate()
        g.onScan(reelsScan(now = 100), now = 100, hideTab = true, useCover = true)
        g.onScan(reelsScan(now = 1400), now = 1400, hideTab = true, useCover = true) // cover up
        val gone = g.onTargetGone(now = 1600)
        assertTrue(gone.any { it is BlockerAction.HideCover })
        assertTrue(gone.any { it is BlockerAction.HidePatch })

        val reEntry = g.onScan(reelsScan(now = 3000), now = 3000, hideTab = true, useCover = true)
        assertEquals(1, reEntry.count { it is BlockerAction.PressBack })
    }

    @Test
    fun `cover disabled never shows the cover`() {
        val g = gate()
        g.onScan(reelsScan(now = 100), now = 100, hideTab = true, useCover = false)
        val actions = g.onScan(reelsScan(now = 5000), now = 5000, hideTab = true, useCover = false)
        assertTrue(actions.none { it is BlockerAction.ShowCover })
    }

    @Test
    fun `tab suppression disabled never shows the patch`() {
        val g = gate()
        val actions = g.onScan(cleanScan(withTab = true), now = 0, hideTab = false, useCover = true)
        assertTrue(actions.none { it is BlockerAction.ShowPatch })
        assertTrue(actions.any { it is BlockerAction.HidePatch })
    }
}
