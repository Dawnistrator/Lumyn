package com.lumyn.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Standard five-step Material 3 shape scale (the stable 1.4.x set, no Expressive tokens). */
val LumynShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Inner radius for content nested in a rounded container: `outer - padding`, never below 4 dp. */
fun nestedCornerRadius(outerRadius: Dp, padding: Dp): Dp = (outerRadius - padding).coerceAtLeast(4.dp)
