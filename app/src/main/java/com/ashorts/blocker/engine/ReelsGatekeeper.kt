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
 *  - The Reels screen must be detected on TWO consecutive scans before anything happens, so a
 *    transient false positive can never touch the user (stories once tripped the single-scan
 *    version of this rule).
 *  - Once confirmed, aShorts presses Back once, instantly: the feed closes and its audio stops.
 *  - Detections within [settleMs] of a Back are the old screen lingering during the transition
 *    and are ignored.
 *  - Still in Reels after that? Instagram can stack several Reels screens (shared links,
 *    DMs), so one Back is not always enough: press Back again, up to [maxBacks] in total.
 *    Only when that is exhausted does the cover show.
 *  - The cover's Leave button hides the cover and restarts the same Back sequence, so a single
 *    tap gets out even through a stacked back history (one Back per tap used to land on the
 *    next Reels screen and bring the cover straight back: the "press Leave twice" bug).
 *  - A scan without Reels hides the cover immediately and shows the tab patch when the tab is
 *    present.
 *  - [onTargetGone] resets everything (used when the watched app leaves the screen or blocking
 *    is off).
 */
class ReelsGatekeeper(
    private val settleMs: Long = 700,
    private val maxBacks: Int = 3,
    private val patchBoundsFor: (NodeHandle) -> IntBounds
) {

    private var reelsVisible = false
    private var pendingEntryScans = 0
    private var backsPressed = 0
    private var lastBackAt = 0L
    private var covered = false

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
                pendingEntryScans++
                if (pendingEntryScans >= 2) {
                    // Confirmed entry into Reels: leave instantly (also stops the audio).
                    reelsVisible = true
                    pendingEntryScans = 0
                    actions += pressBack(now, first = true)
                }
                // First detection only: wait for a second consecutive scan.
            } else if (!covered && now - lastBackAt >= settleMs) {
                if (backsPressed < maxBacks) {
                    actions += pressBack(now, first = false)
                } else if (useCover) {
                    covered = true
                    actions += BlockerAction.ShowCover
                }
            }
        } else {
            reelsVisible = false
            pendingEntryScans = 0
            backsPressed = 0
            covered = false
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
        covered = false
        pendingEntryScans = 0
        return listOf(BlockerAction.HideCover, pressBack(now, first = true))
    }

    /** The watched app is no longer on screen; reset all state. */
    fun onTargetGone(now: Long): List<BlockerAction> {
        reelsVisible = false
        pendingEntryScans = 0
        backsPressed = 0
        lastBackAt = 0L
        covered = false
        return listOf(BlockerAction.HideCover, BlockerAction.HidePatch)
    }

    private fun pressBack(now: Long, first: Boolean): BlockerAction {
        backsPressed = if (first) 1 else backsPressed + 1
        lastBackAt = now
        return BlockerAction.PressBack
    }
}
