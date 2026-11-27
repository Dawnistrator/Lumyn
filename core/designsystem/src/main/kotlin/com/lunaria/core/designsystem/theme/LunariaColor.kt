package com.lunaria.core.designsystem.theme

import androidx.compose.material3.ColorScheme

/** Lunaria brand palette for Android 8-11 and for when wallpaper colour is switched off. */
val LunariaLightColorScheme: ColorScheme by lazy { buildColorSchemeFromSeed(LunariaBrandSeedArgb, darkTheme = false) }

val LunariaDarkColorScheme: ColorScheme by lazy { buildColorSchemeFromSeed(LunariaBrandSeedArgb, darkTheme = true) }
