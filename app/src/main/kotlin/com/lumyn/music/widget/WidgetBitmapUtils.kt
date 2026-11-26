package com.lumyn.music.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Downsamples album artwork for Glance home widgets while enforcing Android's
 * RemoteViews bitmap memory cap (`6 * screenWidth * screenHeight`).
 */
object WidgetBitmapUtils {

    fun calculateWidgetMemoryCapBytes(context: Context): Long {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val (width, height) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && windowManager != null) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width().toLong() to bounds.height().toLong()
        } else {
            val dm = context.resources.displayMetrics
            dm.widthPixels.toLong() to dm.heightPixels.toLong()
        }
        return (6L * width * height).coerceAtLeast(MIN_SAFE_CAP_BYTES)
    }

    suspend fun decodeDownsampledArtwork(
        context: Context,
        uriString: String?,
        targetSizePx: Int,
        maxBudgetBytes: Long,
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (uriString.isNullOrBlank()) return@withContext null
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return@withContext null

        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            val srcW = bounds.outWidth
            val srcH = bounds.outHeight
            if (srcW <= 0 || srcH <= 0) return@withContext null

            var sampleSize = 1
            while ((srcW / (sampleSize * 2)) >= targetSizePx && (srcH / (sampleSize * 2)) >= targetSizePx) {
                sampleSize *= 2
            }

            while (estimatedBitmapBytes(srcW / sampleSize, srcH / sampleSize) > maxBudgetBytes) {
                sampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOpts)
            }
        }.getOrNull()
    }

    private fun estimatedBitmapBytes(width: Int, height: Int): Long =
        width.coerceAtLeast(1).toLong() * height.coerceAtLeast(1).toLong() * 4L

    private const val MIN_SAFE_CAP_BYTES = 1_500_000L
}
