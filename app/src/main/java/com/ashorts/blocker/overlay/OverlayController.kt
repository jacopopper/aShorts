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
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.ashorts.blocker.engine.IntBounds

/**
 * Draws the two overlays over Instagram:
 *
 *  1. a small opaque patch exactly on top of the Reels tab in the bottom bar — the tab becomes
 *     invisible and untappable because the overlay window also swallows touches inside its bounds;
 *  2. a full-screen blocker shown when the Reels feed itself is on screen (opened from a DM,
 *     a link, or anywhere else), with a single obvious way out.
 *
 * All windows are TYPE_APPLICATION_OVERLAY (requires the "display over other apps" permission),
 * are non-focusable so the system back gesture still reaches the app behind, and are removed the
 * moment Instagram is no longer visible.
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

    private fun Int.dp(): Int = (this * density + 0.5f).toInt()

    private fun canDraw(): Boolean = Settings.canDrawOverlays(context)

    private fun isNight(): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun overlayFlags(): Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

    // ---------------------------------------------------------------- full-screen blocker

    fun showBlocker(onLeave: () -> Unit, onHome: () -> Unit, onOpenApp: () -> Unit) {
        if (!canDraw() || isBlockerShowing) return

        val night = isNight()
        val bg = if (night) 0xFF0B0E12.toInt() else 0xFFFAFAF8.toInt()
        val textPrimary = if (night) 0xFFE7EAEE.toInt() else 0xFF191C21.toInt()
        val textSecondary = if (night) 0xFF9AA3AD.toInt() else 0xFF5A6472.toInt()
        val accent = 0xFF2E7D5B.toInt()

        fun lineParams(topMarginDp: Int) =
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = topMarginDp.dp() }

        fun text(
            sizeSp: Float,
            value: String,
            color: Int,
            bold: Boolean = false,
            underline: Boolean = false,
            params: LinearLayout.LayoutParams
        ): TextView = TextView(context).apply {
            text = value
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            gravity = Gravity.CENTER
            if (bold) paint.isFakeBoldText = true
            if (underline) paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            layoutParams = params
        }

        val leave = Button(context).apply {
            text = "Leave Reels"
            setTextColor(Color.WHITE)
            transformationMethod = null
            background = GradientDrawable().apply { setColor(accent); cornerRadius = 14f * density }
            setOnClickListener { onLeave() }
            layoutParams = lineParams(28)
        }

        val home = Button(context).apply {
            text = "Go to Home screen"
            setTextColor(textSecondary)
            transformationMethod = null
            background = null
            setOnClickListener { onHome() }
            layoutParams = lineParams(4)
        }

        val openApp = text(14f, "Open aShorts settings", accent, underline = true, params = lineParams(24))
        openApp.setOnClickListener { onOpenApp() }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32.dp(), 32.dp(), 32.dp(), 32.dp())
            setBackgroundColor(bg)
            addView(text(26f, "Reels are blocked", textPrimary, bold = true, params = lineParams(0)))
            addView(text(15f, "aShorts is keeping you out of the endless scroll.", textSecondary, params = lineParams(8)))
            addView(leave)
            addView(home)
            addView(openApp)
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            overlayFlags() or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
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
        val view = patchView
        if (view == null) {
            val v = createPatchView()
            try {
                wm.addView(v, patchParams(bounds))
                patchView = v
                isPatchShowing = true
            } catch (_: Exception) {
                // Ignore; will retry on the next scan.
            }
        } else {
            runCatching { wm.updateViewLayout(view, patchParams(bounds)) }
        }
    }

    fun hidePatch() {
        val v = patchView ?: return
        runCatching { wm.removeView(v) }
        patchView = null
        isPatchShowing = false
    }

    fun hideAll() {
        hideBlocker()
        hidePatch()
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
     * Opaque patch matched to Instagram's bottom bar (black in dark mode, white in light mode)
     * with rounded top corners. Touches inside its bounds land on this window instead of the
     * Reels tab underneath.
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
