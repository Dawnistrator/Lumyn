package com.lumyn.core.model

import kotlin.math.abs

/** ISO standard 10-band graphic equalizer center frequencies (Hz). */
val StandardEqFrequenciesHz: List<Int> = listOf(
    31, 62, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000,
)

enum class ReplayGainMode {
    OFF,
    TRACK,
    ALBUM,
}

enum class ParametricFilterType {
    PEAKING,
    LOW_SHELF,
    HIGH_SHELF,
}

data class ParametricFilter(
    val frequencyHz: Double,
    val gainDb: Double,
    val qFactor: Double,
    val type: ParametricFilterType = ParametricFilterType.PEAKING,
    val enabled: Boolean = true,
)

data class EqualizerPreset(
    val name: String,
    val bandGainsDb: List<Float>,
    val preampDb: Float = 0f,
)

val BuiltInEqPresets: List<EqualizerPreset> = listOf(
    EqualizerPreset(
        name = "Flat",
        bandGainsDb = List(10) { 0f },
        preampDb = 0f,
    ),
    EqualizerPreset(
        name = "Bass Boost",
        bandGainsDb = listOf(5.0f, 4.5f, 3.0f, 1.5f, 0f, 0f, 0f, 0f, 0f, 0f),
        preampDb = -3.0f,
    ),
    EqualizerPreset(
        name = "Vocal",
        bandGainsDb = listOf(-2.0f, -1.5f, -0.5f, 1.0f, 2.5f, 3.5f, 3.0f, 1.5f, 0.5f, -0.5f),
        preampDb = -2.0f,
    ),
    EqualizerPreset(
        name = "Acoustic",
        bandGainsDb = listOf(2.5f, 2.0f, 1.0f, 0.5f, 1.0f, 1.5f, 2.0f, 2.5f, 2.0f, 1.5f),
        preampDb = -1.5f,
    ),
    EqualizerPreset(
        name = "Treble Lift",
        bandGainsDb = listOf(0f, 0f, 0f, 0f, 0f, 0.5f, 1.5f, 3.0f, 4.0f, 4.5f),
        preampDb = -2.0f,
    ),
    EqualizerPreset(
        name = "Studio Reference",
        bandGainsDb = listOf(1.5f, 1.0f, 0.5f, 0f, -0.5f, 0f, 0.5f, 1.0f, 1.5f, 1.0f),
        preampDb = -1.0f,
    ),
)

data class EqualizerSettings(
    val enabled: Boolean = false,
    val preampDb: Float = 0f,
    val bandGainsDb: List<Float> = List(10) { 0f },
    val parametricFilters: List<ParametricFilter> = emptyList(),
    val activePresetName: String = "Flat",
    val replayGainMode: ReplayGainMode = ReplayGainMode.OFF,
    val replayGainPreampDb: Float = 0f,
    val channelBalance: Float = 0f,
    val monoAudio: Boolean = false,
    val crossfadeDurationSec: Int = 0,
) {
    fun normalizedBandGains(): List<Float> = List(StandardEqFrequenciesHz.size) { index ->
        bandGainsDb.getOrElse(index) { 0f }.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
    }

    fun hasActiveEffects(): Boolean {
        val eqActive = enabled && (
            abs(preampDb) >= 0.05f ||
                normalizedBandGains().any { abs(it) >= 0.05f } ||
                parametricFilters.any { it.enabled && abs(it.gainDb) >= 0.05 }
            )
        val replayGainActive = replayGainMode != ReplayGainMode.OFF
        val balanceOrMonoActive = abs(channelBalance) >= 0.01f || monoAudio
        return eqActive || replayGainActive || balanceOrMonoActive
    }

    companion object {
        const val MIN_GAIN_DB = -12f
        const val MAX_GAIN_DB = 12f
        const val MAX_CROSSFADE_SEC = 12
    }
}
