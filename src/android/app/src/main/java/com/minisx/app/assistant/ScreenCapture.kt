package com.minisx.app.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import com.minisx.app.accessibility.MinisAccessibilityService
import com.minisx.app.logging.AppLogger
import com.minisx.app.offload.OffloadPermissionManager
import com.minisx.app.offload.ShizukuManager
import java.io.File

/**
 * Gets the screen as the user last saw it, quietly and before any MinisX window is up.
 *
 * In order: a bitmap the system itself handed the assistant session (the caller passes it), the
 * accessibility service's screenshot, then `screencap` through Shizuku. Shizuku is used only when the
 * user has set `android-shizuku-cli` to "Allow" (an "Ask" policy would put a dialog on top of the very
 * screen being captured). Apps that block capture (FLAG_SECURE), the lock screen and some banking apps
 * give nothing or black; the invocation then goes on with the text alone.
 */
object ScreenCapture {
    private const val TAG = "ScreenCapture"
    private const val JPEG_QUALITY = 85

    /** The screen from our own means, written to [dir] as a JPEG; null if none worked. */
    fun captureOwn(context: Context, dir: File): File? {
        runCatching {
            val svc = MinisAccessibilityService.getInstance()
            if (svc != null && Build.VERSION.SDK_INT >= 30) {
                val shot = svc.captureScreenshot(timeoutMs = 2_500)
                shot.bitmap?.let { bmp -> return save(bmp, dir) }
            }
        }.onFailure { AppLogger.warning(TAG, "accessibility capture failed: ${it.message}") }
        return captureViaShizuku(dir)
    }

    private fun captureViaShizuku(dir: File): File? {
        return try {
            ShizukuManager.refresh()
            if (ShizukuManager.snapshot.value.state != ShizukuManager.State.READY) return null
            if (OffloadPermissionManager.getLevel("shizuku_cli") != OffloadPermissionManager.PermissionLevel.BYPASS) return null
            val shared = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Screenshots/.minisx-assist.png")
            shared.parentFile?.mkdirs()
            val r = ShizukuManager.runProcess(arrayOf("sh", "-c", "screencap -p '${shared.path}'"), timeoutMs = 6_000L)
            if (r.exitCode != 0 || !shared.exists()) return null
            val bmp = BitmapFactory.decodeFile(shared.path)
            shared.delete()
            bmp?.let { save(it, dir) }
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "shizuku capture failed: ${e.message}")
            null
        }
    }

    /** Scales [bitmap] to at most [AssistantContext.MAX_SIDE] and writes it; the bitmap is recycled. */
    fun save(bitmap: Bitmap, dir: File): File? {
        return try {
            dir.mkdirs()
            val (w, h) = AssistantContext.fit(bitmap.width, bitmap.height)
            val scaled = if (w != bitmap.width || h != bitmap.height) Bitmap.createScaledBitmap(bitmap, w, h, true) else bitmap
            val file = File(dir, "screen-${System.currentTimeMillis()}.jpg")
            file.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
            file.takeIf { it.length() > 0 }
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "save failed: ${e.message}")
            null
        }
    }

    /** Old screenshots are not kept. */
    fun clean(dir: File, keep: Int = 4) {
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(keep)?.forEach { it.delete() }
    }
}
