package com.lumyn.feature.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.compose.animation.animateColorAsState
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.lumyn.core.designsystem.theme.LumynMotionTokens
import com.lumyn.core.designsystem.theme.LumynTheme
import com.lumyn.core.designsystem.theme.extractAlbumArtColorScheme
import com.lumyn.core.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Wraps [content] with an album-art derived M3 [ColorScheme] (API 26+) when [enabled] is true
 * and artwork can be sampled, smoothly animating colours without overshoot.
 */
@Composable
fun AnimatedAlbumArtTheme(
    track: Track?,
    enabled: Boolean,
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val fallbackScheme = MaterialTheme.colorScheme
    val key = if (enabled) (track?.artworkUri ?: track?.contentUri) else null

    val extractedScheme by produceState<ColorScheme?>(
        initialValue = null,
        key1 = key,
        key2 = darkTheme,
    ) {
        if (key == null || track == null) {
            value = null
            return@produceState
        }
        val bitmap = withContext(Dispatchers.IO) { loadSampledArtworkBitmap(context, track) }
        value = if (bitmap != null) {
            try {
                extractAlbumArtColorScheme(bitmap, darkTheme = darkTheme)
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        } else {
            null
        }
    }

    val target = extractedScheme ?: fallbackScheme
    val animated = animateColorScheme(target)

    LumynTheme(
        darkTheme = darkTheme,
        dynamicColor = false,
        overrideColorScheme = animated,
        content = content,
    )
}

@Composable
private fun animateColorScheme(target: ColorScheme): ColorScheme {
    @Composable
    fun anim(color: Color, label: String): Color =
        animateColorAsState(targetValue = color, animationSpec = LumynMotionTokens.effectsDefault(), label = label).value

    return target.copy(
        primary = anim(target.primary, "primary"),
        onPrimary = anim(target.onPrimary, "onPrimary"),
        primaryContainer = anim(target.primaryContainer, "primaryContainer"),
        onPrimaryContainer = anim(target.onPrimaryContainer, "onPrimaryContainer"),
        secondary = anim(target.secondary, "secondary"),
        onSecondary = anim(target.onSecondary, "onSecondary"),
        secondaryContainer = anim(target.secondaryContainer, "secondaryContainer"),
        onSecondaryContainer = anim(target.onSecondaryContainer, "onSecondaryContainer"),
        tertiary = anim(target.tertiary, "tertiary"),
        onTertiary = anim(target.onTertiary, "onTertiary"),
        tertiaryContainer = anim(target.tertiaryContainer, "tertiaryContainer"),
        onTertiaryContainer = anim(target.onTertiaryContainer, "onTertiaryContainer"),
        background = anim(target.background, "background"),
        onBackground = anim(target.onBackground, "onBackground"),
        surface = anim(target.surface, "surface"),
        onSurface = anim(target.onSurface, "onSurface"),
        surfaceVariant = anim(target.surfaceVariant, "surfaceVariant"),
        onSurfaceVariant = anim(target.onSurfaceVariant, "onSurfaceVariant"),
        surfaceContainerLowest = anim(target.surfaceContainerLowest, "surfaceContainerLowest"),
        surfaceContainerLow = anim(target.surfaceContainerLow, "surfaceContainerLow"),
        surfaceContainer = anim(target.surfaceContainer, "surfaceContainer"),
        surfaceContainerHigh = anim(target.surfaceContainerHigh, "surfaceContainerHigh"),
        surfaceContainerHighest = anim(target.surfaceContainerHighest, "surfaceContainerHighest"),
        outline = anim(target.outline, "outline"),
        outlineVariant = anim(target.outlineVariant, "outlineVariant"),
    )
}

private fun loadSampledArtworkBitmap(context: Context, track: Track): Bitmap? {
    val resolver = context.contentResolver
    val maxDimension = 128

    track.artworkUri?.let { raw ->
        runCatching {
            val uri = Uri.parse(raw)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                var sample = 1
                val largest = maxOf(bounds.outWidth, bounds.outHeight)
                while (largest / sample > maxDimension * 2) sample *= 2
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                resolver.openInputStream(uri)?.use { stream ->
                    return BitmapFactory.decodeStream(stream, null, opts)
                }
            }
        }
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        return runCatching {
            resolver.loadThumbnail(Uri.parse(track.contentUri), Size(maxDimension, maxDimension), null)
        }.getOrNull()
    }
    return null
}
