package com.lumyn.core.designsystem

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.lumyn.core.designsystem.theme.LumynBrandSeedArgb
import com.lumyn.core.designsystem.theme.LumynDarkColorScheme
import com.lumyn.core.designsystem.theme.LumynLightColorScheme
import com.lumyn.core.designsystem.theme.buildColorSchemeFromSeed
import com.lumyn.core.designsystem.theme.contrastRatio
import com.lumyn.core.designsystem.theme.extractSeedColorFromPixels
import com.lumyn.core.designsystem.theme.nestedCornerRadius
import androidx.compose.material3.ColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumArtColorSchemeTest {

    private val minTextContrast = 4.5

    private fun ColorScheme.rolePairs(): Map<String, Pair<Int, Int>> = mapOf(
        "onPrimary/primary" to (onPrimary.toArgb() to primary.toArgb()),
        "onPrimaryContainer/primaryContainer" to (onPrimaryContainer.toArgb() to primaryContainer.toArgb()),
        "onSecondaryContainer/secondaryContainer" to (onSecondaryContainer.toArgb() to secondaryContainer.toArgb()),
        "onTertiaryContainer/tertiaryContainer" to (onTertiaryContainer.toArgb() to tertiaryContainer.toArgb()),
        "onErrorContainer/errorContainer" to (onErrorContainer.toArgb() to errorContainer.toArgb()),
        "onSurface/surface" to (onSurface.toArgb() to surface.toArgb()),
        "onSurface/surfaceContainerHigh" to (onSurface.toArgb() to surfaceContainerHigh.toArgb()),
        "onSurfaceVariant/surfaceContainer" to (onSurfaceVariant.toArgb() to surfaceContainer.toArgb()),
    )

    private fun assertReadable(name: String, scheme: ColorScheme) {
        scheme.rolePairs().forEach { (pair, colors) ->
            val ratio = contrastRatio(colors.first, colors.second)
            assertTrue("$name $pair contrast $ratio < $minTextContrast", ratio >= minTextContrast)
        }
    }

    @Test
    fun brandSchemesMeetTextContrast() {
        assertReadable("brand-light", LumynLightColorScheme)
        assertReadable("brand-dark", LumynDarkColorScheme)
    }

    @Test
    fun extremeCoversStillProduceReadableSchemes() {
        val covers = mapOf(
            "white" to IntArray(256) { 0xFFFFFFFF.toInt() },
            "black" to IntArray(256) { 0xFF000000.toInt() },
            "neon-green" to IntArray(256) { 0xFF39FF14.toInt() },
            "neon-magenta" to IntArray(256) { 0xFFFF00FF.toInt() },
            "empty" to IntArray(0),
        )
        covers.forEach { (name, pixels) ->
            val seed = extractSeedColorFromPixels(pixels)
            listOf(false, true).forEach { dark ->
                assertReadable("$name dark=$dark", buildColorSchemeFromSeed(seed, dark))
            }
        }
    }

    @Test
    fun flatWhiteAndBlackFallBackToBrandSeed() {
        assertEquals(LumynBrandSeedArgb, extractSeedColorFromPixels(IntArray(64) { 0xFFFFFFFF.toInt() }))
        assertEquals(LumynBrandSeedArgb, extractSeedColorFromPixels(IntArray(64) { 0xFF000000.toInt() }))
        assertEquals(LumynBrandSeedArgb, extractSeedColorFromPixels(IntArray(0)))
    }

    @Test
    fun nestedCornerRadiusSubtractsPaddingWithFloor() {
        assertEquals(8.dp, nestedCornerRadius(16.dp, 8.dp))
        assertEquals(4.dp, nestedCornerRadius(8.dp, 8.dp))
    }
}
