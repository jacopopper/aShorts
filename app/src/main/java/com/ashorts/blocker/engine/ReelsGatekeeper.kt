package com.ashorts.blocker.engine

/**
 * What the service should do after a scan or a user action. Keeping these as plain data makes
 * the whole decision flow testable on the JVM.
 */
sealed class BlockerAction {
    object PressBack : BlockerAction()
    data class TapHome(val home: NodeHandle) : BlockerAction()
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
 *    version of this rule). A tap on the Reels tab just before is proof enough for one scan.
 *  - How Reels is left depends on where it was opened:
 *     - from its own tab (tap seen, or the Reels tab is the selected one): tap Home. Reels is a
 *       root tab, so Back lands on Home and the next Back shows Instagram's "tap again to
 *       exit" and then closes the app;
 *     - from somewhere else with the bottom bar showing (profile, Home feed): Back once, which
 *       returns to that screen, then Home if still in Reels; never Back while on Home;
 *     - without the bar (DMs, shared links): Back, up to [maxBacks] times, because Instagram can
 *       stack several Reels screens.
 *  - Detections within [settleMs] of an exit are the old screen lingering during the transition
 *    and are ignored. Only when every exit step failed does the cover show, and not for a Reels
 *    screen that is in the tree but hidden behind the bar's other tabs.
 *  - The cover's Leave button hides the cover and restarts the same exit steps, so a single
 *    tap gets out even through a stacked back history.
 *  - A scan without Reels hides the cover immediately and covers the Reels tab when it is shown.
 *  - [onTargetGone] resets everything (used when the watched app leaves the screen or blocking
 *    is off).
 */
class ReelsGatekeeper(
    private val settleMs: Long = 700,
    private val maxBacks: Int = 3,
    private val tapWindowMs: Long = 2000,
    private val patchBoundsFor: (NodeHandle) -> IntBounds
) {

    private enum class Step { BACK, HOME }

    private var reelsVisible = false
    private var pendingEntryScans = 0
    private var covered = false
    private var lastExitAt = 0L
    private var tabTappedAt: Long? = null

    private var plan: List<Step> = emptyList()
    private var nextStep = 0
    private var backsUsed = 0
    private var barVisible = false
    private var home: NodeHandle? = null

    fun onScan(result: DetectionEngine.ScanResult, now: Long): List<BlockerAction> {
        home = result.homeTab
        if (!result.reelsScreenDetected) {
            reelsVisible = false
            pendingEntryScans = 0
            covered = false
            return listOf(BlockerAction.HideCover) + patchFor(result)
        }

        val actions = mutableListOf<BlockerAction>(BlockerAction.Record(result.matched, screenDetected = true))
        if (!reelsVisible) {
            val tapped = tappedRecently(now)
            pendingEntryScans++
            actions += BlockerAction.HidePatch
            if (pendingEntryScans >= 2 || tapped) {
                reelsVisible = true
                pendingEntryScans = 0
                startPlan(result, tapped)
                nextExit(now)?.let { actions += it }
            }
            // First detection only: wait for a second consecutive scan.
            return actions
        }

        if (covered || now - lastExitAt < settleMs) {
            actions += BlockerAction.HidePatch
            return actions
        }
        val exit = nextExit(now)
        when {
            exit != null -> {
                actions += BlockerAction.HidePatch
                actions += exit
            }
            // A Reels screen kept loaded behind the bar's other tabs: nothing to leave.
            barVisible && !result.reelsScreenVisible -> actions += patchFor(result)
            else -> {
                covered = true
                actions += BlockerAction.HidePatch
                actions += BlockerAction.ShowCover
            }
        }
        return actions
    }

    /**
     * The user tapped the Reels tab (only possible before the patch is up). Switching to Home
     * right away beats waiting for the Reels screen to load.
     */
    fun onReelsTabTapped(now: Long, homeTab: NodeHandle?): List<BlockerAction> {
        tabTappedAt = now
        if (reelsVisible) return emptyList()
        val target = homeTab ?: return emptyList() // detection confirms on one scan instead
        home = target
        reelsVisible = true
        pendingEntryScans = 0
        covered = false
        barVisible = true
        plan = listOf(Step.HOME, Step.HOME)
        nextStep = 1
        backsUsed = 0
        lastExitAt = now
        return listOf(
            BlockerAction.Record(listOf("reels tab tapped: switching to Home"), screenDetected = true),
            BlockerAction.TapHome(target)
        )
    }

    /** The cover's Leave button was pressed. */
    fun onManualLeave(now: Long): List<BlockerAction> {
        covered = false
        pendingEntryScans = 0
        nextStep = 0
        backsUsed = 0
        return listOfNotNull(BlockerAction.HideCover, nextExit(now))
    }

    /** The watched app is no longer on screen; reset all state. */
    fun onTargetGone(now: Long): List<BlockerAction> {
        reelsVisible = false
        pendingEntryScans = 0
        lastExitAt = 0L
        covered = false
        tabTappedAt = null
        plan = emptyList()
        home = null
        return listOf(BlockerAction.HideCover, BlockerAction.HidePatch)
    }

    private fun tappedRecently(now: Long): Boolean =
        tabTappedAt?.let { now - it <= tapWindowMs } ?: false

    private fun startPlan(result: DetectionEngine.ScanResult, tapped: Boolean) {
        val tab = result.reelsTab
        barVisible = tab != null || tapped
        val onHome = result.homeTab?.isSelected == true
        plan = when {
            tapped || tab?.isSelected == true || (tab != null && onHome) -> listOf(Step.HOME, Step.HOME)
            tab != null -> listOf(Step.BACK, Step.HOME)
            else -> List(maxBacks) { Step.BACK }
        }
        nextStep = 0
        backsUsed = 0
    }

    private fun nextExit(now: Long): BlockerAction? {
        while (nextStep < plan.size) {
            val step = plan[nextStep++]
            val target = home
            if (step == Step.HOME && target != null) {
                lastExitAt = now
                return BlockerAction.TapHome(target)
            }
            // No Home tab found: a single Back is the fallback; a second one could close Instagram.
            if (step == Step.BACK || backsUsed == 0) {
                backsUsed++
                lastExitAt = now
                return BlockerAction.PressBack
            }
        }
        return null
    }

    private fun patchFor(result: DetectionEngine.ScanResult): List<BlockerAction> {
        val tab = result.reelsTab ?: return listOf(BlockerAction.HidePatch)
        return listOf(
            BlockerAction.Record(result.matched, screenDetected = false),
            BlockerAction.ShowPatch(patchBoundsFor(tab))
        )
    }
}
