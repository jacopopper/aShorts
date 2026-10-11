package com.ashorts.blocker.overlay

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.ashorts.blocker.engine.IntBounds
import com.ashorts.blocker.log.DetectionLog

/**
 * Draws the two overlays over the watched app:
 *
 *  1. a patch exactly on top of the Reels tab cell in the bottom bar — invisible (it matches the
 *     bar color) and untappable, because the overlay window swallows touches inside its bounds.
 *     The patch is *self-calibrating*: after being placed it reads where the window actually
 *     rendered and corrects any OEM offset, logging the correction (so misplacement reports come
 *     with exact numbers).
 *  2. a minimal full-screen cover, used only as a fallback when aShorts' automatic back-press
 *     fails to leave the Reels feed (deep links, stubborn back stacks).
 */
class OverlayController(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = context.resources.displayMetrics.density

    var isBlockerShowing = false
        private set
    var isPatchShowing = false
        private set

    private var blockerView: View? = null
    private var patchView: View? = null

    /** Last bounds we asked the window manager for, and persistent calibration offsets. */
    private var lastApplied: IntBounds? = null
    private var offsetX = 0
    private var offsetY = 0

    private fun Int.dp(): Int = (this * density + 0.5f).toInt()

    private fun canDraw(): Boolean = Settings.canDrawOverlays(context)

    private fun isNight(): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun overlayFlags(): Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    // ---------------------------------------------------------------- fallback cover

    /** Minimal cover: one mark, one line, one way out. */
    fun showBlocker(onLeave: () -> Unit) {
        if (!canDraw() || isBlockerShowing) return

        val night = isNight()
        val bg = if (night) 0xFF0B0E12.toInt() else 0xFFFAFAF8.toInt()
        val textPrimary = if (night) 0xFFE7EAEE.toInt() else 0xFF191C21.toInt()
        val textSecondary = if (night) 0xFF9AA3AD.toInt() else 0xFF5A6472.toInt()
        val accent = 0xFF2E7D5B.toInt()

        fun params(topMarginDp: Int) =
            LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = topMarginDp.dp(); gravity = Gravity.CENTER_HORIZONTAL }

        fun text(
            sizeSp: Float,
            value: String,
            color: Int,
            bold: Boolean = false,
            letterSpacing: Float = 0f,
            params: LinearLayout.LayoutParams
        ): TextView = TextView(context).apply {
            text = value
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            gravity = Gravity.CENTER
            if (bold) paint.isFakeBoldText = true
            if (letterSpacing != 0f) this.letterSpacing = letterSpacing
            layoutParams = params
        }

        val leave = Button(context).apply {
            text = "Leave"
            setTextColor(Color.WHITE)
            transformationMethod = null
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            minWidth = 220.dp()
            setMinHeight(52.dp())
            background = GradientDrawable().apply {
                setColor(accent)
                cornerRadius = 26f * density
            }
            setOnClickListener { onLeave() }
            layoutParams = params(40)
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32.dp(), 32.dp(), 32.dp(), 32.dp())
            setBackgroundColor(bg)
            addView(text(12f, "ASHORTS", textSecondary, bold = true, letterSpacing = 0.25f, params = params(0)))
            addView(text(28f, "Reels is blocked", textPrimary, bold = true, params = params(10)))
            addView(text(15f, "The scroll ends here.", textSecondary, params = params(8)))
            addView(leave)
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            overlayFlags(),
            PixelFormat.OPAQUE
        )
        try {
            wm.addView(root, lp)
            blockerView = root
            isBlockerShowing = true
        } catch (_: Exception) {
            // Permission revoked or window token gone; never crash the service.
        }
    }

    fun hideBlocker() {
        val v = blockerView ?: return
        runCatching { wm.removeView(v) }
        blockerView = null
        isBlockerShowing = false
    }

    // ---------------------------------------------------------------- Reels tab patch

    fun showPatch(bounds: IntBounds) {
        if (!canDraw()) return
        // Apply the calibration learned so far; skip no-op updates so a static bar does not
        // churn the window manager every scan.
        val target = IntBounds(
            bounds.left + offsetX,
            bounds.top + offsetY,
            bounds.right + offsetX,
            bounds.bottom + offsetY
        )
        if (target == lastApplied && patchView != null) return
        lastApplied = target

        val view = patchView
        if (view == null) {
            val v = createPatchView()
            try {
                wm.addView(v, patchParams(target))
                patchView = v
                isPatchShowing = true
            } catch (_: Exception) {
                // Ignore; will retry on the next scan.
                return
            }
        } else {
            runCatching { wm.updateViewLayout(view, patchParams(target)) }
        }
        scheduleCalibration()
    }

    fun hidePatch() {
        val v = patchView ?: return
        runCatching { wm.removeView(v) }
        patchView = null
        isPatchShowing = false
        lastApplied = null
    }

    fun hideAll() {
        hideBlocker()
        hidePatch()
    }

    /** Rotation/theme changes invalidate placement; forget the learned offsets. */
    fun resetCalibration() {
        offsetX = 0
        offsetY = 0
        lastApplied = null
    }

    /**
     * Read where the patch window *actually* rendered and correct any offset. This is the fix
     * for OEM window managers that place overlay windows with a status-bar/inset shift: the
     * patch measures itself and moves to the right place, reporting what it saw.
     */
    private fun scheduleCalibration() {
        val view = patchView ?: return
        view.post { calibrate(view) }
    }

    private fun calibrate(view: View) {
        if (!view.isAttachedToWindow) return // stale callback from a view we already removed
        val intended = lastApplied ?: return
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val corrected = PatchCalibration.correct(intended, loc[0], loc[1])
        if (corrected == intended) return

        offsetX += corrected.left - intended.left
        offsetY += corrected.top - intended.top
        lastApplied = corrected
        DetectionLog.add(
            listOf(
                "patch calibration: asked=[${intended.left},${intended.top}] " +
                    "rendered=[${loc[0]},${loc[1]}] corrected=[${corrected.left},${corrected.top}] " +
                    "(dx=${corrected.left - intended.left}, dy=${corrected.top - intended.top})"
            ),
            screenDetected = false
        )
        runCatching { wm.updateViewLayout(view, patchParams(corrected)) }

        // Verify the correction landed; if it still drifts, say so (numbers for the next fix).
        view.post {
            val again = IntArray(2)
            view.getLocationOnScreen(again)
            if (again[0] != corrected.left || again[1] != corrected.top) {
                DetectionLog.add(
                    listOf(
                        "patch calibration: still drifting, rendered=[${again[0]},${again[1]}] " +
                            "wanted=[${corrected.left},${corrected.top}]"
                    ),
                    screenDetected = false
                )
            }
        }
    }

    private fun patchParams(b: IntBounds): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            maxOf(b.width, 1),
            maxOf(b.height, 1),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            overlayFlags(),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = b.left
            y = b.top
        }

    /**
     * Opaque patch matched to the bottom bar (black in dark mode, white in light mode) with
     * rounded top corners. Touches inside its bounds land on this window instead of the Reels
     * tab underneath.
     */
    private fun createPatchView(): View = View(context).apply {
        background = GradientDrawable().apply {
            setColor(if (isNight()) Color.BLACK else Color.WHITE)
            cornerRadii = floatArrayOf(
                20f * density, 20f * density,
                20f * density, 20f * density,
                0f, 0f, 0f, 0f
            )
        }
    }
}
