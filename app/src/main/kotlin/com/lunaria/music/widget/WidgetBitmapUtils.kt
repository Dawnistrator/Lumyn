package com.lunaria.music.widget

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
            if (bounds.width() > 0 && bounds.height() > 0) {
                bounds.width().toLong() to bounds.height().toLong()
            } else {
                val dm = context.resources.displayMetrics
                dm.widthPixels.toLong() to dm.heightPixels.toLong()
            }
        } else {
            val dm = context.resources.displayMetrics
            dm.widthPixels.toLong() to dm.heightPixels.toLong()
        }
        return (6L * width * height).coerceAtLeast(MIN_SAFE_CAP_BYTES)
    }

    internal fun calculateInSampleSize(
        srcWidth: Int,
        srcHeight: Int,
        targetPx: Int,
        maxBudgetBytes: Long,
    ): Int {
        if (srcWidth <= 0 || srcHeight <= 0 || targetPx <= 0) return 1
        var sampleSize = 1
        while (((srcWidth / (sampleSize * 2)) >= targetPx || (srcHeight / (sampleSize * 2)) >= targetPx) &&
            (srcWidth / (sampleSize * 2)) > 0 &&
            (srcHeight / (sampleSize * 2)) > 0
        ) {
            sampleSize *= 2
        }
        val safeBudget = maxBudgetBytes.coerceAtLeast(2L)
        while (estimatedBitmapBytes(srcWidth / sampleSize, srcHeight / sampleSize) > safeBudget && sampleSize < 1024) {
            sampleSize *= 2
        }
        return sampleSize
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

            val sampleSize = calculateInSampleSize(
                srcWidth = srcW,
                srcHeight = srcH,
                targetPx = targetSizePx,
                maxBudgetBytes = maxBudgetBytes,
            )

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOpts)
            }
        }.getOrNull()
    }

    private fun estimatedBitmapBytes(width: Int, height: Int): Long =
        width.coerceAtLeast(1).toLong() * height.coerceAtLeast(1).toLong() * 2L

    private const val MIN_SAFE_CAP_BYTES = 1_500_000L
}
