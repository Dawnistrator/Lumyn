package com.lumyn.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Two-layer theme:
 * 1. [overrideColorScheme] (album-art theme for Now Playing) wins when present.
 * 2. Otherwise wallpaper Dynamic Color on Android 12+ when [dynamicColor] is on.
 * 3. Otherwise the Lumyn brand scheme (always used on Android 8-11).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LumynTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    overrideColorScheme: ColorScheme? = null,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        overrideColorScheme != null -> overrideColorScheme
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> LumynDarkColorScheme
        else -> LumynLightColorScheme
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        shapes = LumynShapes,
        typography = LumynTypography,
        content = content,
    )
}
