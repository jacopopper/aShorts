package com.ashorts.blocker.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import com.ashorts.blocker.engine.IntBounds
import com.ashorts.blocker.engine.PatchColorMath
import com.ashorts.blocker.log.DetectionLog

/**
 * Samples the watched app's bottom-bar color so the patch can match it exactly, whatever shade
 * the app actually uses. Uses AccessibilityService.takeScreenshot() (Android 11+; the service
 * declares android:canTakeScreenshot in its config). All failures degrade to the fallback
 * color and are logged with a reason.
 *
 * Sample points sit outside the covered cell (see [PatchColorMath.samplePoints]) so we never
 * read our own patch, and the median over several pixels ignores glyph fragments.
 */
class BarColorSampler(private val service: AccessibilityService) {

    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** [onSampled] is always called on the main thread. */
    fun sample(cellBounds: IntBounds, onSampled: (Int) -> Unit) {
        if (!isSupported) return
        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                service.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        val buffer = screenshot.hardwareBuffer
                        try {
                            val hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                            val software = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                            hardware?.recycle()
                            if (software == null) {
                                fail("could not read screenshot")
                                return
                            }
                            val pixels = PatchColorMath
                                .samplePoints(cellBounds, software.width, software.height)
                                .map { (x, y) -> software.getPixel(x, y) }
                            software.recycle()
                            val color = PatchColorMath.pickColor(pixels)
                            if (color != null) onSampled(color) else fail("no usable pixels")
                        } catch (e: Exception) {
                            fail(e.javaClass.simpleName)
                        } finally {
                            buffer.close()
                        }
                    }

                    override fun onFailure(errorCode: Int) = fail("code=$errorCode")
                }
            )
        } catch (e: Exception) {
            fail(e.javaClass.simpleName)
        }
    }

    private fun fail(reason: String) {
        DetectionLog.add(listOf("patch color sampling failed: $reason"), screenDetected = false)
    }
}
