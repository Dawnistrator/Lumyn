package com.lunaria.core.audioengine

import kotlin.math.PI
import kotlin.math.cos

/**
 * Pure-Kotlin equal-power cosine crossfade volume calculator for DSP 32-bit Float mode.
 */
object CrossfadeCurve {

    fun computeFadeOutVolume(remainingMs: Long, fadeDurationMs: Long): Float {
        if (fadeDurationMs <= 0L || remainingMs >= fadeDurationMs) return 1f
        if (remainingMs <= 0L) return 0f
        val progress = 1.0 - (remainingMs.toDouble() / fadeDurationMs.toDouble())
        return (0.5 * (1.0 + cos(PI * progress))).toFloat().coerceIn(0f, 1f)
    }

    fun computeFadeInVolume(elapsedMs: Long, fadeDurationMs: Long): Float {
        if (fadeDurationMs <= 0L || elapsedMs >= fadeDurationMs) return 1f
        if (elapsedMs <= 0L) return 0f
        val progress = elapsedMs.toDouble() / fadeDurationMs.toDouble()
        return (0.5 * (1.0 - cos(PI * progress))).toFloat().coerceIn(0f, 1f)
    }
}
