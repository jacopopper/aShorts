package com.ashorts.blocker.engine

/**
 * What the service should do after a scan or a user action. Keeping these as plain data makes
 * the whole decision flow testable on the JVM.
 */
sealed class BlockerAction {
    object PressBack : BlockerAction()
    object ShowCover : BlockerAction()
    object HideCover : BlockerAction()
    data class ShowPatch(val bounds: IntBounds) : BlockerAction()
    object HidePatch : BlockerAction()
    data class Record(val details: List<String>, val screenDetected: Boolean) : BlockerAction()
}

/**
 * The decision logic between detection and effects, extracted from the accessibility service so
 * it can be unit-tested against real-world timelines.
 *
 * Behavior (times in elapsedRealtime milliseconds, supplied by the caller):
 *
 *  - Entering the Reels screen (transition from not-in-Reels): press Back once, instantly.
 *    This closes the feed and stops its audio. If the back works, nothing else ever shows.
 *  - If Reels is still detected [autoLeaveGraceMs] after the automatic back, the cover shows.
 *  - The cover's Leave button hides the cover and presses Back. Right after a manual leave,
 *    the Reels view tree can transiently linger during the back transition; a single detection
 *    in that window is ignored (this was the "press Leave twice" bug: the cover used to reappear
 *    instantly). Only a *second consecutive* detection within [manualLeaveGraceMs] re-covers —
 *    that means the back genuinely failed.
 *  - A scan without Reels hides the cover immediately (no lingering over the feed) and shows
 *    the tab patch when the tab is present.
 *  - [onTargetGone] resets everything (used when Instagram leaves the screen or blocking is off).
 */
class ReelsGatekeeper(
    private val autoLeaveGraceMs: Long = 1200,
    private val manualLeaveGraceMs: Long = 1500,
    private val patchBoundsFor: (NodeHandle) -> IntBounds
) {

    private var reelsVisible = false
    private var autoLeaveAt = 0L
    private var manualLeaveAt = 0L
    private var detectionsSinceManualLeave = 0

    fun onScan(
        result: DetectionEngine.ScanResult,
        now: Long,
        hideTab: Boolean,
        useCover: Boolean
    ): List<BlockerAction> {
        val actions = mutableListOf<BlockerAction>()

        if (result.reelsScreenDetected) {
            actions += BlockerAction.Record(result.matched, screenDetected = true)
            actions += BlockerAction.HidePatch
            if (!reelsVisible) {
                // Just entered Reels: leave instantly (also stops the audio).
                reelsVisible = true
                autoLeaveAt = now
                detectionsSinceManualLeave = 0
                actions += BlockerAction.PressBack
            } else {
                val withinManualGrace =
                    manualLeaveAt > 0 && now - manualLeaveAt <= manualLeaveGraceMs
                if (withinManualGrace) {
                    // Right after the user pressed Leave: one lingering detection is ignored,
                    // two consecutive ones mean the back genuinely failed.
                    detectionsSinceManualLeave++
                    if (useCover && detectionsSinceManualLeave >= 2) {
                        actions += BlockerAction.ShowCover
                    }
                } else if (useCover && now - autoLeaveAt > autoLeaveGraceMs) {
                    // The automatic back did not get us out (deep link, stubborn back stack).
                    actions += BlockerAction.ShowCover
                }
            }
        } else {
            reelsVisible = false
            detectionsSinceManualLeave = 0
            actions += BlockerAction.HideCover
            val tab = result.reelsTab
            if (hideTab && tab != null) {
                actions += BlockerAction.Record(result.matched, screenDetected = false)
                actions += BlockerAction.ShowPatch(patchBoundsFor(tab))
            } else {
                actions += BlockerAction.HidePatch
            }
        }
        return actions
    }

    /** The cover's Leave button was pressed. */
    fun onManualLeave(now: Long): List<BlockerAction> {
        manualLeaveAt = now
        detectionsSinceManualLeave = 0
        return listOf(BlockerAction.HideCover, BlockerAction.PressBack)
    }

    /** Instagram (or the watched app) is no longer on screen; reset all state. */
    fun onTargetGone(now: Long): List<BlockerAction> {
        reelsVisible = false
        autoLeaveAt = 0L
        manualLeaveAt = 0L
        detectionsSinceManualLeave = 0
        return listOf(BlockerAction.HideCover, BlockerAction.HidePatch)
    }
}
