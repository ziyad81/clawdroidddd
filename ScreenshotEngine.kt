package com.clawdroid.vision

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/**
 * ScreenshotEngine
 *
 * Two ways to take screenshots:
 *
 * 1. ACCESSIBILITY METHOD (no permission needed):
 *    Uses GLOBAL_ACTION_TAKE_SCREENSHOT via AccessibilityService.
 *    Simple but can't get the bitmap directly.
 *
 * 2. MEDIA PROJECTION METHOD (requires user permission once):
 *    Full screen capture as Bitmap → can send to LLM vision APIs.
 *    Best for AI vision tasks.
 *
 * For maximum power, both methods are implemented here.
 * The agent uses method 2 for vision tasks (describe screen, read text from image, etc.)
 */
object ScreenshotEngine {

    private const val TAG = "ScreenshotEngine"

    // Held after user grants projection permission
    private var mediaProjection: MediaProjection? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    // Callback for when permission result comes back
    var onPermissionResult: ((Boolean) -> Unit)? = null

    // ──────────────────────────────────────────────
    // Setup (call once after user grants permission)
    // ──────────────────────────────────────────────

    fun initialize(context: Context, resultCode: Int, data: Intent) {
        val mgr = context.getSystemService(MediaProjectionManager::class.java)
        mediaProjection = mgr.getMediaProjection(resultCode, data)

        val wm = context.getSystemService(WindowManager::class.java)
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        Log.i(TAG, "ScreenshotEngine initialized: ${screenWidth}x${screenHeight} @ ${screenDensity}dpi")
    }

    fun isReady(): Boolean = mediaProjection != null

    // ──────────────────────────────────────────────
    // Capture screen → Bitmap
    // ──────────────────────────────────────────────

    suspend fun capture(): ScreenshotResult = withContext(Dispatchers.IO) {
        val projection = mediaProjection
            ?: return@withContext ScreenshotResult.error("Screen capture not initialized. Grant screen capture permission first.")

        suspendCancellableCoroutine { cont ->
            try {
                val imageReader = ImageReader.newInstance(
                    screenWidth, screenHeight,
                    PixelFormat.RGBA_8888,
                    2
                )

                val virtualDisplay: VirtualDisplay = projection.createVirtualDisplay(
                    "ClawDroidCapture",
                    screenWidth, screenHeight, screenDensity,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.surface, null, null
                )

                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        val image = imageReader.acquireLatestImage()
                        if (image == null) {
                            virtualDisplay.release()
                            imageReader.close()
                            cont.resume(ScreenshotResult.error("Failed to acquire image"))
                            return@postDelayed
                        }

                        val planes = image.planes
                        val buffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride
                        val rowPadding = rowStride - pixelStride * screenWidth

                        val bitmap = Bitmap.createBitmap(
                            screenWidth + rowPadding / pixelStride,
                            screenHeight,
                            Bitmap.Config.ARGB_8888
                        )
                        bitmap.copyPixelsFromBuffer(buffer)
                        image.close()

                        // Crop to exact screen size
                        val cropped = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)

                        virtualDisplay.release()
                        imageReader.close()

                        cont.resume(ScreenshotResult.success(cropped))
                    } catch (e: Exception) {
                        Log.e(TAG, "Capture failed", e)
                        cont.resume(ScreenshotResult.error("Capture error: ${e.message}"))
                    }
                }, 300) // small delay for screen to settle

            } catch (e: Exception) {
                Log.e(TAG, "VirtualDisplay creation failed", e)
                cont.resume(ScreenshotResult.error("Failed to create virtual display: ${e.message}"))
            }
        }
    }

    // ──────────────────────────────────────────────
    // Save screenshot to file
    // ──────────────────────────────────────────────

    suspend fun captureToFile(context: Context): ScreenshotResult {
        val result = capture()
        if (!result.ok) return result

        return try {
            val dir = File(context.filesDir, "screenshots").also { it.mkdirs() }
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "screenshot_$timestamp.jpg")

            FileOutputStream(file).use { fos ->
                result.bitmap!!.compress(Bitmap.CompressFormat.JPEG, 85, fos)
            }

            Log.d(TAG, "Screenshot saved: ${file.absolutePath}")
            result.copy(filePath = file.absolutePath)
        } catch (e: Exception) {
            ScreenshotResult.error("Save failed: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────
    // Request permission (launch system dialog)
    // ──────────────────────────────────────────────

    fun requestPermission(activity: Activity, requestCode: Int = 1001) {
        val mgr = activity.getSystemService(MediaProjectionManager::class.java)
        activity.startActivityForResult(mgr.createScreenCaptureIntent(), requestCode)
    }

    fun release() {
        mediaProjection?.stop()
        mediaProjection = null
    }
}

data class ScreenshotResult(
    val ok: Boolean,
    val bitmap: Bitmap? = null,
    val filePath: String? = null,
    val error: String? = null
) {
    companion object {
        fun success(bitmap: Bitmap) = ScreenshotResult(true, bitmap)
        fun error(msg: String) = ScreenshotResult(false, error = msg)
    }
}
