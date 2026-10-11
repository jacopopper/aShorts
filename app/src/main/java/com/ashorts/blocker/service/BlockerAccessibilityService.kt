package com.ashorts.blocker.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Point
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.WindowInsets
import android.view.WindowManager
import com.ashorts.blocker.BuildConfig
import com.ashorts.blocker.engine.BlockerAction
import com.ashorts.blocker.engine.DetectionEngine
import com.ashorts.blocker.engine.InstagramRules
import com.ashorts.blocker.engine.IntBounds
import com.ashorts.blocker.engine.ReelsGatekeeper
import com.ashorts.blocker.engine.expandedTabBounds
import com.ashorts.blocker.log.DetectionLog
import com.ashorts.blocker.overlay.OverlayController
import com.ashorts.blocker.prefs.Prefs

/**
 * The always-on part of aShorts. The system delivers accessibility events here; we look at them
 * only while the watched app (Instagram, plus the test harness in debug builds) is on screen.
 *
 * All decisions go through [ReelsGatekeeper] (pure, unit-tested). This class only executes the
 * resulting actions against real system APIs, which keeps the logic testable end-to-end
 * without a device.
 */
class BlockerAccessibilityService : AccessibilityService() {

    private lateinit var overlay: OverlayController
    private lateinit var engine: DetectionEngine
    private lateinit var gatekeeper: ReelsGatekeeper
    private var prefs: SharedPreferences? = null

    /** Real physical screen size — NOT resources.displayMetrics, which excludes system bars. */
    private var screenSize = IntBounds(0, 0, 1080, 2340)

    /** Height of the system navigation bar at the bottom; the patch must stay above it. */
    private var navBarBottomInset = 0

    private var master: Boolean = Prefs.DEFAULT_MASTER
    private var hideTab: Boolean = Prefs.DEFAULT_HIDE_TAB
    private var useBlocker: Boolean = Prefs.DEFAULT_BLOCKER
    private var aggressive: Boolean = Prefs.DEFAULT_AGGRESSIVE

    private var lastScanAt = 0L
    private var overlaysHiddenForOff = false

    /** Instagram plus, in debug builds only, the local test harness. */
    private val watchedPackages: Set<String> =
        if (BuildConfig.EXTRA_WATCHED_PACKAGE.isEmpty()) {
            setOf(InstagramRules.PACKAGE)
        } else {
            setOf(InstagramRules.PACKAGE, BuildConfig.EXTRA_WATCHED_PACKAGE)
        }

    private val prefListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> reloadPrefs() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay = OverlayController(this)
        engine = DetectionEngine(InstagramRules.rules(Prefs.DEFAULT_AGGRESSIVE))
        refreshScreenSize()
        gatekeeper = ReelsGatekeeper { node ->
            expandedTabBounds(
                node,
                screenSize.width,
                screenSize.height,
                maxBottom = screenSize.height - navBarBottomInset
            )
        }
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
        if (this::overlay.isInitialized) {
            overlay.resetCalibration()
            overlay.hidePatch() // re-created (position and dark/light color) on the next scan
        }
        refreshScreenSize()
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
            overlay.barTheme = p.getString(Prefs.KEY_BAR_THEME, Prefs.DEFAULT_BAR_THEME)
                ?: Prefs.DEFAULT_BAR_THEME
        }

        if (this::overlay.isInitialized && this::gatekeeper.isInitialized) {
            if (!master) {
                execute(gatekeeper.onTargetGone(SystemClock.elapsedRealtime()))
                overlaysHiddenForOff = true
            } else {
                overlaysHiddenForOff = false
            }
        }
    }

    /** Physical screen size including system bars (the coordinate space of a11y node bounds). */
    private fun refreshScreenSize() {
        try {
            val wm = getSystemService(WindowManager::class.java) ?: return
            navBarBottomInset = navigationBarBottomInset(wm)
            screenSize = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val b = wm.currentWindowMetrics.bounds
                IntBounds(b.left, b.top, b.right, b.bottom)
            } else {
                @Suppress("DEPRECATION")
                val display = wm.defaultDisplay ?: return
                val p = Point()
                @Suppress("DEPRECATION")
                display.getRealSize(p)
                IntBounds(0, 0, p.x, p.y)
            }
        } catch (_: Exception) {
            // Keep the previous size.
        }
    }

    private fun navigationBarBottomInset(wm: WindowManager): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return wm.currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars())
                .bottom
        }
        // Pre-Android 11: the nav bar sits at the bottom only in portrait.
        if (resources.configuration.orientation != Configuration.ORIENTATION_PORTRAIT) return 0
        @Suppress("DiscouragedApi", "InternalInsetResource")
        val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }


    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return // events from our own overlay windows

        val now = SystemClock.elapsedRealtime()

        if (!master) {
            if (!overlaysHiddenForOff) {
                execute(gatekeeper.onTargetGone(now))
                overlaysHiddenForOff = true
            }
            return
        }
        overlaysHiddenForOff = false

        // Throttle: while the fallback cover is up we only need to notice leaving Reels.
        val throttleMs = if (overlay.isBlockerShowing) 700L else 240L
        if (now - lastScanAt < throttleMs) return
        lastScanAt = now

        // Find the watched app's window root. AccessibilityWindowInfo stopped exposing the
        // package name (removed in Android 13), so we check the root node's package instead.
        var root: AccessibilityNodeInfo? = null
        for (w in windows) {
            val r = w.root ?: continue
            if (r.packageName?.toString() in watchedPackages) {
                root = r
                break
            }
        }
        if (root == null) {
            // Watched app not visible; drop everything and reset.
            execute(gatekeeper.onTargetGone(now))
            return
        }

        val result = engine.scan(
            AccessibilityNodeHandle(root, 0),
            screenSize.width,
            screenSize.height
        )
        execute(gatekeeper.onScan(result, now, hideTab, useBlocker))
    }

    private fun execute(actions: List<BlockerAction>) {
        var pressedBack = false
        for (action in actions) {
            when (action) {
                is BlockerAction.PressBack -> {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    pressedBack = true
                }
                is BlockerAction.ShowCover -> overlay.showBlocker(onLeave = { onManualLeave() })
                is BlockerAction.HideCover -> overlay.hideBlocker()
                is BlockerAction.ShowPatch -> overlay.showPatch(action.bounds)
                is BlockerAction.HidePatch -> overlay.hidePatch()
                is BlockerAction.Record -> DetectionLog.add(action.details, action.screenDetected)
            }
        }
        if (pressedBack) lastScanAt = 0 // re-scan right away; cover returns only if still needed
    }

    private fun onManualLeave() {
        if (this::gatekeeper.isInitialized) {
            execute(gatekeeper.onManualLeave(SystemClock.elapsedRealtime()))
        }
    }

    override fun onInterrupt() {
        // Nothing to do; we never intercept user input.
    }
}
