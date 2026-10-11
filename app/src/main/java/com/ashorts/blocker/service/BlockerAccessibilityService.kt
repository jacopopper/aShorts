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
import com.ashorts.blocker.log.DetectionLog
import com.ashorts.blocker.overlay.OverlayController
import com.ashorts.blocker.prefs.Prefs
import com.ashorts.blocker.ui.MainActivity

/**
 * The always-on part of aShorts. The system delivers accessibility events here; we look at them
 * only when Instagram is on screen:
 *
 *  - no scan runs at all while any other app is in the foreground (and no events are delivered
 *    for our own overlays), so the idle cost is effectively zero;
 *  - scans are throttled (240 ms normally, 700 ms while the blocker is up) and capped in size;
 *  - when the Reels tab is visible in Instagram's bottom bar, a patch overlay covers it;
 *  - when the Reels feed itself is visible (deep link, DM, explore, ...), a full-screen blocker
 *    covers everything until the user leaves.
 */
class BlockerAccessibilityService : AccessibilityService() {

    private lateinit var overlay: OverlayController
    private lateinit var engine: DetectionEngine
    private var prefs: SharedPreferences? = null

    private var master: Boolean = Prefs.DEFAULT_MASTER
    private var hideTab: Boolean = Prefs.DEFAULT_HIDE_TAB
    private var useBlocker: Boolean = Prefs.DEFAULT_BLOCKER
    private var aggressive: Boolean = Prefs.DEFAULT_AGGRESSIVE

    private var lastScanAt = 0L
    private var cleanScans = 0
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
            }
            return
        }
        overlaysHiddenForOff = false

        // Throttle. While the blocker is up we relax the rate: we only need to notice when the
        // user leaves Reels on their own (system back gesture or leaving the app).
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
            if (overlay.isBlockerShowing || overlay.isPatchShowing) {
                overlay.hideAll()
                cleanScans = 0
            }
            return
        }
        val metrics = resources.displayMetrics
        val result = engine.scan(
            AccessibilityNodeHandle(root, 0),
            metrics.widthPixels,
            metrics.heightPixels
        )

        if (result.reelsScreenDetected && useBlocker) {
            cleanScans = 0
            DetectionLog.add(result.matched, screenDetected = true)
            overlay.showBlocker(
                onLeave = {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    lastScanAt = 0 // rescan immediately; blocker returns if we are still in Reels
                },
                onHome = {
                    overlay.hideBlocker()
                    performGlobalAction(GLOBAL_ACTION_HOME)
                },
                onOpenApp = {
                    overlay.hideBlocker()
                    startActivity(
                        Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            )
            overlay.hidePatch()
        } else {
            // Two consecutive clean scans before removing the blocker, to avoid flicker.
            if (overlay.isBlockerShowing && ++cleanScans >= 2) {
                overlay.hideBlocker()
            }
            val tab = result.reelsTabBounds
            if (hideTab && !overlay.isBlockerShowing && tab != null) {
                DetectionLog.add(result.matched, screenDetected = false)
                overlay.showPatch(tab)
            } else {
                overlay.hidePatch()
            }
        }
    }

    override fun onInterrupt() {
        // Nothing to do; we never intercept user input.
    }
}
