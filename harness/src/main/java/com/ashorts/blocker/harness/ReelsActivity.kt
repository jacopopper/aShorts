package com.ashorts.blocker.harness

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

/**
 * The fake Reels screen. Its root view carries the id `clips_viewer_debug_container`, which is
 * the same id aShorts matches in the real Instagram Reels viewer.
 *
 * Back behavior is switchable from the main screen so the E2E suite can exercise both paths:
 * normal (back finishes the activity) and "ignore all backs" (back does nothing, forcing the
 * aShorts fallback cover to appear).
 */
class ReelsActivity : Activity() {

    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var tick = 0

    private val tickTask = object : Runnable {
        override fun run() {
            tick++
            if (tick % 5 == 0) status.text = statusText() // keep content-change events flowing
            handler.postDelayed(this, 200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(HarnessMainActivity.TAG, "REELS_OPENED")

        val root = FrameLayout(this)
        root.id = R.id.clips_viewer_debug_container
        root.setBackgroundColor(Color.BLACK)

        val title = TextView(this).apply {
            text = "FAKE REELS"
            setTextColor(Color.WHITE)
            textSize = 40f
            gravity = Gravity.CENTER
        }
        root.addView(
            title,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
        }
        root.addView(
            status,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL or Gravity.BOTTOM
            )
        )

        setContentView(root)
        status.text = statusText()
        handler.post(tickTask)
    }

    private fun statusText() =
        "Back presses: ${ReelsState.backCount} (ignored=${ReelsState.ignoreAllBacks})"

    override fun onBackPressed() {
        ReelsState.backCount++
        Log.i(
            HarnessMainActivity.TAG,
            "BACK_PRESSED count=${ReelsState.backCount} ignored=${ReelsState.ignoreAllBacks}"
        )
        status.text = statusText()
        if (!ReelsState.ignoreAllBacks) {
            super.onBackPressed()
        }
        // else: stay, simulating a Reels entry that does not respond to back
    }

    override fun onDestroy() {
        handler.removeCallbacks(tickTask)
        super.onDestroy()
    }
}
