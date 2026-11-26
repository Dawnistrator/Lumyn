package com.lumyn.core.designsystem.theme

import androidx.compose.material3.ColorScheme

/** Lumyn brand palette for Android 8-11 and for when wallpaper colour is switched off. */
val LumynLightColorScheme: ColorScheme by lazy { buildColorSchemeFromSeed(LumynBrandSeedArgb, darkTheme = false) }

val LumynDarkColorScheme: ColorScheme by lazy { buildColorSchemeFromSeed(LumynBrandSeedArgb, darkTheme = true) }
