package com.lumyn.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Material 3 Expressive eight-step shape scale for Lumyn containers, cards, and morphing targets. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
val LumynShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    largeIncreased = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
    extraLargeIncreased = RoundedCornerShape(32.dp),
    extraExtraLarge = RoundedCornerShape(48.dp),
)

/** Inner radius for content nested in a rounded container: `outer - padding`, never below 4 dp. */
fun nestedCornerRadius(outerRadius: Dp, padding: Dp): Dp = (outerRadius - padding).coerceAtLeast(4.dp)
