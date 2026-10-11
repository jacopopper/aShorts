package com.ashorts.blocker.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.ashorts.blocker.engine.DetectionEngine
import com.ashorts.blocker.engine.InstagramRules
import com.ashorts.blocker.engine.expandedTabBounds
import com.ashorts.blocker.log.DetectionLog
import com.ashorts.blocker.overlay.OverlayController
import com.ashorts.blocker.prefs.Prefs

/**
 * The always-on part of aShorts. The system delivers accessibility events here; we look at them
 * only when Instagram is on screen:
 *
 *  - no scan runs at all while any other app is in the foreground (and events from our own
 *    overlays are ignored), so the idle cost is effectively zero;
 *  - scans are throttled (240 ms normally, 700 ms while the fallback cover is up) and capped
 *    in size, keeping the battery/memory footprint minimal;
 *  - when the Reels tab is visible in Instagram's bottom bar, an invisible patch covers it;
 *  - when the Reels feed itself appears (deep link, DM, search...), aShorts presses Back
 *    immediately: the feed closes and its audio stops. Only if that fails does the minimal
 *    cover show up, with a single Leave button.
 */
class BlockerAccessibilityService : AccessibilityService() {

    companion object {
        /** How long to wait for the automatic back-press to land before showing the cover. */
        private const val AUTO_LEAVE_GRACE_MS = 1200L
    }

    private lateinit var overlay: OverlayController
    private lateinit var engine: DetectionEngine
    private var prefs: SharedPreferences? = null

    private var master: Boolean = Prefs.DEFAULT_MASTER
    private var hideTab: Boolean = Prefs.DEFAULT_HIDE_TAB
    private var useBlocker: Boolean = Prefs.DEFAULT_BLOCKER
    private var aggressive: Boolean = Prefs.DEFAULT_AGGRESSIVE

    private var lastScanAt = 0L
    private var autoLeaveAt = 0L
    private var reelsVisible = false
    private var overlaysHiddenForOff = false

    private val prefListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> reloadPrefs() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay = OverlayController(this)
        engine = DetectionEngine(InstagramRules.rules(Prefs.DEFAULT_AGGRESSIVE))
        prefs = Prefs.get(this).also { it.registerOnSharedPreferenceChangeListener(prefListener) }
        DetectionLog.init(filesDir)
        reloadPrefs()
    }

    override fun onDestroy() {
        prefs?.unregisterOnSharedPreferenceChangeListener(prefListener)
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // Called when the user disables the service in system settings.
        if (this::overlay.isInitialized) overlay.hideAll()
        return super.onUnbind(intent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Bounds are stale after rotation/theme changes; hide until the next scan repositions it.
        if (this::overlay.isInitialized) overlay.hidePatch()
    }

    private fun reloadPrefs() {
        val p = Prefs.get(this)
        master = p.getBoolean(Prefs.KEY_MASTER, Prefs.DEFAULT_MASTER)
        hideTab = p.getBoolean(Prefs.KEY_HIDE_TAB, Prefs.DEFAULT_HIDE_TAB)
        useBlocker = p.getBoolean(Prefs.KEY_BLOCKER, Prefs.DEFAULT_BLOCKER)
        val newAggressive = p.getBoolean(Prefs.KEY_AGGRESSIVE, Prefs.DEFAULT_AGGRESSIVE)
        if (this::engine.isInitialized && newAggressive != aggressive) {
            engine = DetectionEngine(InstagramRules.rules(newAggressive))
        }
        aggressive = newAggressive

        if (this::overlay.isInitialized) {
            if (!master) {
                overlay.hideAll()
                overlaysHiddenForOff = true
            } else {
                overlaysHiddenForOff = false
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return // events from our own overlay windows

        if (!master) {
            if (!overlaysHiddenForOff) {
                overlay.hideAll()
                overlaysHiddenForOff = true
                reelsVisible = false
            }
            return
        }
        overlaysHiddenForOff = false

        val throttleMs = if (overlay.isBlockerShowing) 700L else 240L
        val now = SystemClock.elapsedRealtime()
        if (now - lastScanAt < throttleMs) return
        lastScanAt = now

        // Find the Instagram window's root node. AccessibilityWindowInfo stopped exposing the
        // package name (removed in Android 13), so we check the root node's package instead.
        var root: AccessibilityNodeInfo? = null
        for (w in windows) {
            val r = w.root ?: continue
            if (r.packageName?.toString() == InstagramRules.PACKAGE) {
                root = r
                break
            }
        }
        if (root == null) {
            // Instagram is not visible; make sure nothing of ours is left on screen.
            if (overlay.isBlockerShowing || overlay.isPatchShowing) overlay.hideAll()
            reelsVisible = false
            return
        }

        val metrics = resources.displayMetrics
        val result = engine.scan(
            AccessibilityNodeHandle(root, 0),
            metrics.widthPixels,
            metrics.heightPixels
        )

        if (result.reelsScreenDetected) {
            DetectionLog.add(result.matched, screenDetected = true)
            overlay.hidePatch()
            if (!reelsVisible) {
                // Just entered Reels: leave instantly. This also stops the audio, and it lands
                // the user back wherever they came from — usually invisible and silent.
                reelsVisible = true
                autoLeaveAt = now
                performGlobalAction(GLOBAL_ACTION_BACK)
                lastScanAt = 0 // re-check immediately so the cover appears if we are still there
            } else if (useBlocker && now - autoLeaveAt > AUTO_LEAVE_GRACE_MS) {
                // The automatic back did not get us out (deep link, stubborn back stack).
                overlay.showBlocker(onLeave = {
                    overlay.hideBlocker()
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    lastScanAt = 0
                })
            }
        } else {
            // Not on Reels. Drop the cover at the first clean scan — no lingering over the feed.
            reelsVisible = false
            if (overlay.isBlockerShowing) overlay.hideBlocker()

            val tab = result.reelsTab
            if (hideTab && tab != null) {
                DetectionLog.add(result.matched, screenDetected = false)
                overlay.showPatch(expandedTabBounds(tab, metrics.widthPixels, metrics.heightPixels))
            } else {
                overlay.hidePatch()
            }
        }
    }

    override fun onInterrupt() {
        // Nothing to do; we never intercept user input.
    }
}
