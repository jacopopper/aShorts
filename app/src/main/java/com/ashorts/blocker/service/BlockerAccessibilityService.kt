package com.ashorts.blocker.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
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
import com.ashorts.blocker.engine.PatchColorMath
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
    private lateinit var colorSampler: BarColorSampler
    private var prefs: SharedPreferences? = null
    private var lastConfiguration: Configuration? = null

    /** Real physical screen size — NOT resources.displayMetrics, which excludes system bars. */
    private var screenSize = IntBounds(0, 0, 1080, 2340)

    /** Height of the system navigation bar at the bottom; the patch must stay above it. */
    private var navBarBottomInset = 0

    private var master: Boolean = Prefs.DEFAULT_MASTER

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
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != Prefs.KEY_CAPTURE_MISSING) reloadPrefs()
        }

    override fun onServiceConnected() {
        super.onServiceConnected()
        lastConfiguration = Configuration(resources.configuration)
        overlay = OverlayController(this)
        colorSampler = BarColorSampler(
            this,
            canSample = { overlay.isPatchShowing && !overlay.isBlockerShowing },
            onAccess = { granted -> setCaptureMissing(!granted) }
        ) { color ->
            if (overlay.setSampledColor(color)) {
                DetectionLog.add(
                    listOf("patch color matched to bar: ${PatchColorMath.hex(color)}"),
                    screenDetected = false
                )
            }
        }
        engine = DetectionEngine(InstagramRules.rules(aggressiveTextMatch = false))
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
        checkCaptureGrant()
    }

    /**
     * After an update that adds a capability, Android keeps the old grant until the service is
     * turned off and on again; the app shows a hint while that is the case.
     */
    private fun checkCaptureGrant() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val caps = serviceInfo?.capabilities ?: return
        val granted = (caps and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT) != 0
        setCaptureMissing(!granted)
        if (!granted) {
            DetectionLog.add(
                listOf("patch color: screen capture not granted yet; turn aShorts off and on"),
                screenDetected = false
            )
        }
    }

    private fun setCaptureMissing(missing: Boolean) {
        val p = prefs ?: return
        if (p.getBoolean(Prefs.KEY_CAPTURE_MISSING, false) != missing) {
            p.edit().putBoolean(Prefs.KEY_CAPTURE_MISSING, missing).apply()
        }
    }

    override fun onDestroy() {
        prefs?.unregisterOnSharedPreferenceChangeListener(prefListener)
        if (this::colorSampler.isInitialized) colorSampler.shutdown()
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // Called when the user disables the service in system settings.
        if (this::overlay.isInitialized) overlay.hideAll()
        if (this::colorSampler.isInitialized) colorSampler.stop()
        return super.onUnbind(intent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val previous = lastConfiguration
        lastConfiguration = Configuration(newConfig)
        if (this::overlay.isInitialized) {
            overlay.onConfigurationChanged(newConfig)
            if (previous == null ||
                previous.orientation != newConfig.orientation ||
                previous.screenWidthDp != newConfig.screenWidthDp ||
                previous.screenHeightDp != newConfig.screenHeightDp ||
                previous.densityDpi != newConfig.densityDpi
            ) {
                overlay.resetCalibration()
                overlay.hidePatch() // display changes need fresh bounds on the next scan
                colorSampler.stop()
            } else {
                // Instagram may or may not follow the system theme, and redraws over a few
                // hundred ms if it does: read the bar at every allowed slot for ~2 s.
                colorSampler.burst(100, 450, 800, 1150, 1500, 2000)
            }
        }
        refreshScreenSize()
    }

    private fun reloadPrefs() {
        val p = Prefs.get(this)
        master = p.getBoolean(Prefs.KEY_MASTER, Prefs.DEFAULT_MASTER)

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

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            if (pkg in watchedPackages) onWatchedClick(event, now)
            return
        }

        // Throttle: while the fallback cover is up we only need to notice leaving Reels.
        val throttleMs = if (overlay.isBlockerShowing) 700L else 240L
        if (now - lastScanAt < throttleMs) return
        lastScanAt = now

        val result = scanWatchedApp()
        if (result == null) {
            // Watched app not visible; drop everything and reset.
            execute(gatekeeper.onTargetGone(now))
            return
        }
        execute(gatekeeper.onScan(result, now))
    }

    /** The Reels tab can only be tapped before the patch is up; react before Reels loads. */
    private fun onWatchedClick(event: AccessibilityEvent, now: Long) {
        val source = event.source ?: return
        if (!engine.isReelsTab(AccessibilityNodeHandle(source, 0), screenSize.width, screenSize.height)) {
            return
        }
        val result = scanWatchedApp()
        execute(gatekeeper.onReelsTabTapped(now, result?.homeTab))
        lastScanAt = 0 // the next event re-checks right away
    }

    private fun scanWatchedApp(): DetectionEngine.ScanResult? {
        // AccessibilityWindowInfo stopped exposing the package name (removed in Android 13),
        // so we check the root node's package instead.
        var root: AccessibilityNodeInfo? = null
        for (w in windows) {
            val r = w.root ?: continue
            if (r.packageName?.toString() in watchedPackages) {
                root = r
                break
            }
        }
        root ?: return null
        return engine.scan(AccessibilityNodeHandle(root, 0), screenSize.width, screenSize.height)
    }

    private fun execute(actions: List<BlockerAction>) {
        var pressedBack = false
        for (action in actions) {
            when (action) {
                is BlockerAction.PressBack -> {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    pressedBack = true
                }
                is BlockerAction.TapHome -> {
                    if (!action.home.click()) {
                        DetectionLog.add(listOf("could not tap the Home tab"), screenDetected = true)
                    }
                    pressedBack = true
                }
                is BlockerAction.ShowCover -> overlay.showBlocker(onLeave = { onManualLeave() })
                is BlockerAction.HideCover -> overlay.hideBlocker()
                is BlockerAction.ShowPatch -> {
                    colorSampler.setTarget(action.bounds)
                    if (overlay.showPatch(action.bounds)) {
                        colorSampler.burst(0, 400, 1000)
                    } else {
                        // Catches Instagram's own theme setting, which sends no configuration change.
                        colorSampler.requestIfOlderThan(SAMPLE_REFRESH_MS)
                    }
                }
                is BlockerAction.HidePatch -> {
                    overlay.hidePatch()
                    colorSampler.stop()
                }
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

    private companion object {
        const val SAMPLE_REFRESH_MS = 700L
    }

    override fun onInterrupt() {
        // Nothing to do; we never intercept user input.
    }
}
