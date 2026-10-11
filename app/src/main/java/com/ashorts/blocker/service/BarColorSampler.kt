package com.ashorts.blocker.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import androidx.annotation.RequiresApi
import com.ashorts.blocker.engine.IntBounds
import com.ashorts.blocker.engine.PatchColorMath
import com.ashorts.blocker.log.DetectionLog
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Reads the bar color right next to the patch from an accessibility screenshot (Android 11+).
 * The screenshot is decoded off the main thread, only a few pixels are read, and the image is
 * dropped immediately; nothing is stored.
 *
 * Android rejects screenshots requested less than ~333 ms apart, so requests are coalesced
 * into the next allowed slot instead of being lost.
 */
class BarColorSampler(
    private val service: AccessibilityService,
    private val canSample: () -> Boolean,
    /** false when Android refuses screen capture (stale grant after an update). */
    private val onAccess: (Boolean) -> Unit,
    private val onColor: (Int) -> Unit
) {

    private val handler = Handler(Looper.getMainLooper())
    private val decoder: ExecutorService = Executors.newSingleThreadExecutor()

    private var cell: IntBounds? = null
    private var lastShotAt = 0L
    private var inFlight = false
    private var again = false
    private var retryPosted = false
    private var lastFailure: String? = null

    /** The covered cell; samples are taken just outside it. */
    fun setTarget(bounds: IntBounds) {
        cell = bounds
    }

    fun stop() {
        cell = null
        again = false
        retryPosted = false
        handler.removeCallbacksAndMessages(null)
    }

    fun shutdown() {
        stop()
        decoder.shutdownNow()
    }

    /** Several samples over time, for changes the app takes a moment to redraw. */
    fun burst(vararg delaysMs: Long) {
        for (delay in delaysMs) {
            if (delay <= 0) request() else handler.postDelayed({ request() }, delay)
        }
    }

    fun requestIfOlderThan(maxAgeMs: Long) {
        if (SystemClock.elapsedRealtime() - lastShotAt >= maxAgeMs) request()
    }

    fun request() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || cell == null || !canSample()) return
        if (inFlight) {
            again = true
            return
        }
        val wait = lastShotAt + MIN_INTERVAL_MS - SystemClock.elapsedRealtime()
        if (wait > 0) {
            if (!retryPosted) {
                retryPosted = true
                handler.postDelayed({
                    retryPosted = false
                    request()
                }, wait)
            }
            return
        }
        shoot()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun shoot() {
        val target = cell ?: return
        inFlight = true
        lastShotAt = SystemClock.elapsedRealtime()
        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                decoder,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        val color = decode(screenshot, target)
                        handler.post {
                            onAccess(true)
                            finish(color, null)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                            handler.post {
                                again = true
                                finish(null, null)
                            }
                        } else {
                            handler.post {
                                if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS) {
                                    onAccess(false)
                                }
                                finish(null, "code=$errorCode")
                            }
                        }
                    }
                }
            )
        } catch (e: SecurityException) {
            onAccess(false)
            finish(null, "screen capture not granted")
        } catch (e: Exception) {
            finish(null, e.javaClass.simpleName)
        }
    }

    /** Runs on [decoder]. */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun decode(screenshot: AccessibilityService.ScreenshotResult, target: IntBounds): Int? {
        val buffer = screenshot.hardwareBuffer
        return try {
            val hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace) ?: return null
            val software = hardware.copy(Bitmap.Config.ARGB_8888, false)
            hardware.recycle()
            software ?: return null
            val pixels = PatchColorMath
                .samplePoints(target, software.width, software.height)
                .map { (x, y) -> software.getPixel(x, y) }
            software.recycle()
            PatchColorMath.pickColor(pixels)
        } catch (_: Exception) {
            null
        } finally {
            buffer.close()
        }
    }

    private fun finish(color: Int?, failure: String?) {
        inFlight = false
        if (failure == null) lastFailure = null
        if (failure != null && failure != lastFailure) {
            lastFailure = failure
            DetectionLog.add(listOf("patch color sampling failed: $failure"), screenDetected = false)
        }
        // The cover may have appeared while the screenshot was in flight; it would be the
        // color we just read.
        if (color != null && cell != null && canSample()) onColor(color)
        if (again) {
            again = false
            request()
        }
    }

    private companion object {
        const val MIN_INTERVAL_MS = 350L
    }
}
