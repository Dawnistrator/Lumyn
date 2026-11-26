package com.lumyn.core.designsystem.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * Lumyn motion tokens built on standard Compose animation APIs, so they follow the
 * system "Remove animations" setting.
 *
 * - Spatial: movement and size (mini-player <-> Now Playing, Play/Pause corner morph, queue reorder).
 * - Effects: colour, alpha, scrim. Critically damped, never overshoots.
 */
object LumynMotionTokens {

    const val DurationShortMs = 150
    const val DurationMediumMs = 300
    const val DurationLongMs = 500

    val EmphasizedEasing: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerateEasing: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerateEasing: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    fun <T> spatialFast(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.6f, stiffness = 800f)
    fun <T> spatialDefault(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.8f, stiffness = 380f)
    fun <T> spatialSlow(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.8f, stiffness = 200f)

    fun <T> effectsFast(): FiniteAnimationSpec<T> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 3800f)

    fun <T> effectsDefault(): FiniteAnimationSpec<T> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 1600f)

    fun <T> effectsSlow(): FiniteAnimationSpec<T> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 800f)

    fun <T> emphasizedTween(durationMillis: Int = DurationMediumMs): FiniteAnimationSpec<T> =
        tween(durationMillis = durationMillis, easing = EmphasizedEasing)
}
