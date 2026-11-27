package com.lunaria.core.designsystem.theme

import android.graphics.Bitmap
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.materialkolor.contrast.Contrast
import com.materialkolor.hct.Hct
import com.materialkolor.quantize.QuantizerCelebi
import com.materialkolor.scheme.DynamicScheme
import com.materialkolor.scheme.SchemeTonalSpot
import com.materialkolor.score.Score
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Seed used when no usable colour can be taken from an album cover. */
const val LunariaBrandSeedArgb: Int = 0xFF32428A.toInt()

private const val QuantizerMaxColors = 128

/**
 * Picks the most representative, non-grey colour from [pixels] (ARGB ints).
 * Falls back to [fallbackSeedArgb] for empty, flat white/black or low-chroma artwork.
 */
fun extractSeedColorFromPixels(
    pixels: IntArray,
    fallbackSeedArgb: Int = LunariaBrandSeedArgb,
): Int {
    if (pixels.isEmpty()) return fallbackSeedArgb
    val quantized = QuantizerCelebi.quantize(pixels, QuantizerMaxColors)
    val ranked = Score.score(quantized, 1, fallbackSeedArgb, true)
    return ranked.firstOrNull() ?: fallbackSeedArgb
}

/** Builds a full Material 3 [ColorScheme] (all roles) from a single seed colour. */
fun buildColorSchemeFromSeed(
    seedArgb: Int,
    darkTheme: Boolean,
    contrastLevel: Double = 0.0,
): ColorScheme {
    val scheme = SchemeTonalSpot(Hct.fromInt(seedArgb), darkTheme, contrastLevel)
    return scheme.toColorScheme(darkTheme)
}

private fun DynamicScheme.toColorScheme(darkTheme: Boolean): ColorScheme {
    fun c(argb: Int) = Color(argb)
    return if (darkTheme) {
        darkColorScheme(
            primary = c(primary),
            onPrimary = c(onPrimary),
            primaryContainer = c(primaryContainer),
            onPrimaryContainer = c(onPrimaryContainer),
            inversePrimary = c(inversePrimary),
            secondary = c(secondary),
            onSecondary = c(onSecondary),
            secondaryContainer = c(secondaryContainer),
            onSecondaryContainer = c(onSecondaryContainer),
            tertiary = c(tertiary),
            onTertiary = c(onTertiary),
            tertiaryContainer = c(tertiaryContainer),
            onTertiaryContainer = c(onTertiaryContainer),
            background = c(background),
            onBackground = c(onBackground),
            surface = c(surface),
            onSurface = c(onSurface),
            surfaceVariant = c(surfaceVariant),
            onSurfaceVariant = c(onSurfaceVariant),
            surfaceTint = c(primary),
            inverseSurface = c(inverseSurface),
            inverseOnSurface = c(inverseOnSurface),
            error = c(error),
            onError = c(onError),
            errorContainer = c(errorContainer),
            onErrorContainer = c(onErrorContainer),
            outline = c(outline),
            outlineVariant = c(outlineVariant),
            scrim = c(scrim),
            surfaceBright = c(surfaceBright),
            surfaceContainer = c(surfaceContainer),
            surfaceContainerHigh = c(surfaceContainerHigh),
            surfaceContainerHighest = c(surfaceContainerHighest),
            surfaceContainerLow = c(surfaceContainerLow),
            surfaceContainerLowest = c(surfaceContainerLowest),
            surfaceDim = c(surfaceDim),
            primaryFixed = c(primaryFixed),
            primaryFixedDim = c(primaryFixedDim),
            onPrimaryFixed = c(onPrimaryFixed),
            onPrimaryFixedVariant = c(onPrimaryFixedVariant),
            secondaryFixed = c(secondaryFixed),
            secondaryFixedDim = c(secondaryFixedDim),
            onSecondaryFixed = c(onSecondaryFixed),
            onSecondaryFixedVariant = c(onSecondaryFixedVariant),
            tertiaryFixed = c(tertiaryFixed),
            tertiaryFixedDim = c(tertiaryFixedDim),
            onTertiaryFixed = c(onTertiaryFixed),
            onTertiaryFixedVariant = c(onTertiaryFixedVariant),
        )
    } else {
        lightColorScheme(
            primary = c(primary),
            onPrimary = c(onPrimary),
            primaryContainer = c(primaryContainer),
            onPrimaryContainer = c(onPrimaryContainer),
            inversePrimary = c(inversePrimary),
            secondary = c(secondary),
            onSecondary = c(onSecondary),
            secondaryContainer = c(secondaryContainer),
            onSecondaryContainer = c(onSecondaryContainer),
            tertiary = c(tertiary),
            onTertiary = c(onTertiary),
            tertiaryContainer = c(tertiaryContainer),
            onTertiaryContainer = c(onTertiaryContainer),
            background = c(background),
            onBackground = c(onBackground),
            surface = c(surface),
            onSurface = c(onSurface),
            surfaceVariant = c(surfaceVariant),
            onSurfaceVariant = c(onSurfaceVariant),
            surfaceTint = c(primary),
            inverseSurface = c(inverseSurface),
            inverseOnSurface = c(inverseOnSurface),
            error = c(error),
            onError = c(onError),
            errorContainer = c(errorContainer),
            onErrorContainer = c(onErrorContainer),
            outline = c(outline),
            outlineVariant = c(outlineVariant),
            scrim = c(scrim),
            surfaceBright = c(surfaceBright),
            surfaceContainer = c(surfaceContainer),
            surfaceContainerHigh = c(surfaceContainerHigh),
            surfaceContainerHighest = c(surfaceContainerHighest),
            surfaceContainerLow = c(surfaceContainerLow),
            surfaceContainerLowest = c(surfaceContainerLowest),
            surfaceDim = c(surfaceDim),
            primaryFixed = c(primaryFixed),
            primaryFixedDim = c(primaryFixedDim),
            onPrimaryFixed = c(onPrimaryFixed),
            onPrimaryFixedVariant = c(onPrimaryFixedVariant),
            secondaryFixed = c(secondaryFixed),
            secondaryFixedDim = c(secondaryFixedDim),
            onSecondaryFixed = c(onSecondaryFixed),
            onSecondaryFixedVariant = c(onSecondaryFixedVariant),
            tertiaryFixed = c(tertiaryFixed),
            tertiaryFixedDim = c(tertiaryFixedDim),
            onTertiaryFixed = c(onTertiaryFixed),
            onTertiaryFixedVariant = c(onTertiaryFixedVariant),
        )
    }
}

/**
 * Reads a seed colour from [bitmap]. Hardware bitmaps are copied to software first,
 * and large bitmaps are scaled down so extraction stays cheap.
 */
fun extractSeedColorFromBitmap(
    bitmap: Bitmap,
    maxDimension: Int = 128,
    fallbackSeedArgb: Int = LunariaBrandSeedArgb,
): Int {
    if (bitmap.width <= 0 || bitmap.height <= 0) return fallbackSeedArgb

    var working = bitmap
    val temporaries = mutableListOf<Bitmap>()
    try {
        if (working.config == Bitmap.Config.HARDWARE) {
            val software = working.copy(Bitmap.Config.ARGB_8888, false) ?: return fallbackSeedArgb
            temporaries += software
            working = software
        }
        val largest = maxOf(working.width, working.height)
        if (largest > maxDimension) {
            val ratio = maxDimension.toFloat() / largest
            val scaled = Bitmap.createScaledBitmap(
                working,
                (working.width * ratio).toInt().coerceAtLeast(1),
                (working.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
            if (scaled !== working) temporaries += scaled
            working = scaled
        }
        val pixels = IntArray(working.width * working.height)
        working.getPixels(pixels, 0, working.width, 0, 0, working.width, working.height)
        return extractSeedColorFromPixels(pixels, fallbackSeedArgb)
    } finally {
        temporaries.forEach { if (!it.isRecycled) it.recycle() }
    }
}

/** Off-main-thread album-art theme: bitmap -> seed -> full colour scheme. */
suspend fun extractAlbumArtColorScheme(
    bitmap: Bitmap,
    darkTheme: Boolean,
    contrastLevel: Double = 0.0,
): ColorScheme = withContext(Dispatchers.Default) {
    buildColorSchemeFromSeed(extractSeedColorFromBitmap(bitmap), darkTheme, contrastLevel)
}

/** WCAG contrast ratio (1..21) between two ARGB colours. */
fun contrastRatio(foregroundArgb: Int, backgroundArgb: Int): Double =
    Contrast.ratioOfTones(Hct.fromInt(foregroundArgb).tone, Hct.fromInt(backgroundArgb).tone)
