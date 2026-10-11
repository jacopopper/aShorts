package com.ashorts.blocker.harness

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Point
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A tiny fake Instagram used by the emulator E2E suite. It reproduces exactly the structure
 * aShorts detects in the real app:
 *
 *  - a bottom bar with a clickable "Reels" tab whose resource id is `clips_tab`;
 *  - a separate Reels screen whose root id is `clips_viewer_debug_container`.
 *
 * Everything is positioned in absolute screen pixels (edge-to-edge window), so the test driver
 * knows exactly where every control is. The tickers generate content-change events, like
 * Instagram's constantly animating feed, so the accessibility service keeps scanning.
 */
class HarnessMainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ReelsState.backCount = 0
        ReelsState.ignoreAllBacks = false
        Log.i(TAG, "MAIN_SHOWN")

        // Edge-to-edge window so programmatic coordinates == screen coordinates.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        val wm = getSystemService(android.view.WindowManager::class.java)
        val size = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val p = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay?.getRealSize(p)
            p.x to p.y
        }
        val w = size.first
        val h = size.second

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.WHITE)

        fun place(v: View, x: Int, y: Int, width: Int, height: Int) {
            val lp = FrameLayout.LayoutParams(width, height)
            lp.leftMargin = x
            lp.topMargin = y
            root.addView(v, lp)
        }

        val marker = TextView(this).apply {
            text = "HARNESS MAIN"
            setTextColor(Color.BLACK)
            textSize = 20f
        }
        place(marker, 24, 24, 600, 80)

        val open = Button(this).apply {
            id = R.id.harness_open_link
            text = "OPEN REELS (LINK)"
            transformationMethod = null
            setOnClickListener {
                startActivity(Intent(this@HarnessMainActivity, ReelsActivity::class.java))
            }
        }
        place(open, w / 2 - 240, h * 30 / 100, 480, 140)

        val ignore = CheckBox(this).apply {
            id = R.id.harness_ignore_backs
            text = "IGNORE ALL BACKS"
            setTextColor(Color.BLACK)
            textSize = 16f
            setOnCheckedChangeListener { _, checked -> ReelsState.ignoreAllBacks = checked }
        }
        place(ignore, w / 2 - 240, h * 45 / 100, 480, 120)

        val backs = TextView(this).apply {
            id = R.id.harness_back_count
            setTextColor(Color.BLACK)
            textSize = 18f
            gravity = Gravity.CENTER
            text = "Back presses: ${ReelsState.backCount}"
        }
        place(backs, w / 2 - 240, h * 55 / 100, 480, 80)

        val ticker = TextView(this).apply {
            setTextColor(Color.DKGRAY)
            textSize = 14f
            gravity = Gravity.CENTER
        }
        place(ticker, w / 2 - 240, h * 65 / 100, 480, 80)

        // Bottom tab bar, floating 150..300px above the screen bottom (clear of any nav bar).
        // Five equal cells; the second is the "Reels" tab, just like Instagram.
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
        }
        val labels = listOf("Home", "Reels", "Search", "Likes", "You")
        labels.forEachIndexed { i, label ->
            val cell = TextView(this).apply {
                text = label
                setTextColor(Color.BLACK)
                textSize = 20f
                gravity = Gravity.CENTER
                if (i == 1) {
                    id = R.id.clips_tab
                    contentDescription = "Reels"
                    isClickable = true
                    setOnClickListener {
                        startActivity(Intent(this@HarnessMainActivity, ReelsActivity::class.java))
                    }
                }
            }
            bar.addView(cell, LinearLayout.LayoutParams(w / 5, 150))
        }
        place(bar, 0, h - 300, w, 150)

        setContentView(root)

        val handler = Handler(Looper.getMainLooper())
        var tick = 0
        val tickTask = object : Runnable {
            override fun run() {
                tick++
                ticker.text = "tick $tick"
                backs.text = "Back presses: ${ReelsState.backCount}"
                handler.postDelayed(this, 300)
            }
        }
        handler.post(tickTask)
    }

    companion object {
        const val TAG = "Harness"
    }
}

object ReelsState {
    var backCount = 0
    var ignoreAllBacks = false
}
