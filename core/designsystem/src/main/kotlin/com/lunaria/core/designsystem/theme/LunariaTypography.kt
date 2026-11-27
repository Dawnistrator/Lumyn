package com.lunaria.core.designsystem.theme

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.lunaria.core.designsystem.R

val JetBrainsMonoFontFamily = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(R.font.jetbrains_mono_semibold, FontWeight.SemiBold),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

private fun monoStyle(
    size: Int,
    line: Int,
    tracking: Float,
    weight: FontWeight,
) = TextStyle(
    fontFamily = JetBrainsMonoFontFamily,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
)

/**
 * The 15 standard Material 3 type roles backed by offline bundled JetBrains Mono,
 * tuned for fixed-advance legibility across compact screens and 200% font scale.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
val LunariaTypography = Typography(
    displayLarge = monoStyle(52, 60, -0.5f, FontWeight.Normal),
    displayMedium = monoStyle(42, 50, -0.25f, FontWeight.Normal),
    displaySmall = monoStyle(34, 42, -0.25f, FontWeight.Normal),
    headlineLarge = monoStyle(30, 38, -0.25f, FontWeight.Normal),
    headlineMedium = monoStyle(26, 34, -0.2f, FontWeight.Normal),
    headlineSmall = monoStyle(22, 30, -0.2f, FontWeight.Medium),
    titleLarge = monoStyle(20, 26, -0.2f, FontWeight.Medium),
    titleMedium = monoStyle(15, 22, -0.1f, FontWeight.Medium),
    titleSmall = monoStyle(13, 18, -0.1f, FontWeight.Medium),
    bodyLarge = monoStyle(15, 22, 0f, FontWeight.Normal),
    bodyMedium = monoStyle(13, 19, 0f, FontWeight.Normal),
    bodySmall = monoStyle(11, 16, 0f, FontWeight.Normal),
    labelLarge = monoStyle(13, 18, 0f, FontWeight.Medium),
    labelMedium = monoStyle(11, 15, 0f, FontWeight.Medium),
    labelSmall = monoStyle(10, 14, 0f, FontWeight.Medium),
    displayLargeEmphasized = monoStyle(52, 60, -0.5f, FontWeight.Bold),
    displayMediumEmphasized = monoStyle(42, 50, -0.25f, FontWeight.Bold),
    displaySmallEmphasized = monoStyle(34, 42, -0.25f, FontWeight.Bold),
    headlineLargeEmphasized = monoStyle(30, 38, -0.25f, FontWeight.Bold),
    headlineMediumEmphasized = monoStyle(26, 34, -0.2f, FontWeight.SemiBold),
    headlineSmallEmphasized = monoStyle(22, 30, -0.2f, FontWeight.SemiBold),
    titleLargeEmphasized = monoStyle(20, 26, -0.2f, FontWeight.Bold),
    titleMediumEmphasized = monoStyle(15, 22, -0.1f, FontWeight.SemiBold),
    titleSmallEmphasized = monoStyle(13, 18, -0.1f, FontWeight.Bold),
    bodyLargeEmphasized = monoStyle(15, 22, 0f, FontWeight.SemiBold),
    bodyMediumEmphasized = monoStyle(13, 19, 0f, FontWeight.Medium),
    bodySmallEmphasized = monoStyle(11, 16, 0f, FontWeight.Medium),
    labelLargeEmphasized = monoStyle(13, 18, 0f, FontWeight.Bold),
    labelMediumEmphasized = monoStyle(11, 15, 0f, FontWeight.SemiBold),
    labelSmallEmphasized = monoStyle(10, 14, 0f, FontWeight.SemiBold),
)
