package com.lumyn.core.model

import kotlin.math.log10

/** Pure-Kotlin ReplayGain & R128 parser and clipping-safe gain calculator. */
object ReplayGainCalculator {

    private val GainRegex = Regex("""([+-]?\d+(?:[.,]\d+)?)""")

    fun parseGainDb(raw: String?): Float? {
        if (raw.isNullOrBlank()) return null
        val match = GainRegex.find(raw.trim()) ?: return null
        val normalized = match.groupValues[1].replace(',', '.')
        val value = normalized.toFloatOrNull() ?: return null
        return value.coerceIn(-30f, 30f)
    }

    fun parsePeak(raw: String?): Float? {
        if (raw.isNullOrBlank()) return null
        val match = GainRegex.find(raw.trim()) ?: return null
        val normalized = match.groupValues[1].replace(',', '.')
        val value = normalized.toFloatOrNull() ?: return null
        return value.takeIf { it > 0f && it <= 8f }
    }

    /**
     * Parses RFC 7845 `R128_TRACK_GAIN` / `R128_ALBUM_GAIN` (Q7.8 fixed-point relative to -23 LUFS)
     * and converts it to the ReplayGain -18 LUFS target reference (+5.0 dB).
     */
    fun parseR128GainDb(raw: String?): Float? {
        val intValue = raw?.trim()?.toIntOrNull() ?: return null
        val lufsDeltaDb = intValue / 256.0f
        return (lufsDeltaDb + R128_TO_REPLAYGAIN_OFFSET_DB).coerceIn(-30f, 30f)
    }

    /**
     * Computes effective ReplayGain adjustment (dB) including manual preamp and peak clipping prevention.
     * Returns `null` when [mode] is [ReplayGainMode.OFF].
     */
    fun computeEffectiveGainDb(
        mode: ReplayGainMode,
        trackGainDb: Float?,
        albumGainDb: Float?,
        trackPeak: Float?,
        albumPeak: Float?,
        preampDb: Float,
    ): Float? {
        if (mode == ReplayGainMode.OFF) return null

        val rawGain = when (mode) {
            ReplayGainMode.OFF -> return null
            ReplayGainMode.TRACK -> trackGainDb ?: albumGainDb ?: 0f
            ReplayGainMode.ALBUM -> albumGainDb ?: trackGainDb ?: 0f
        }
        val activePeak = when (mode) {
            ReplayGainMode.OFF -> null
            ReplayGainMode.TRACK -> trackPeak ?: albumPeak
            ReplayGainMode.ALBUM -> albumPeak ?: trackPeak
        }

        val desiredGain = rawGain + preampDb
        return if (activePeak != null && activePeak > 0f) {
            val maxSafeGainDb = (-20.0 * log10(activePeak.toDouble())).toFloat()
            minOf(desiredGain, maxSafeGainDb).coerceIn(-24f, 24f)
        } else {
            desiredGain.coerceIn(-24f, 24f)
        }
    }

    fun hasTagForMode(
        mode: ReplayGainMode,
        trackGainDb: Float?,
        albumGainDb: Float?,
    ): Boolean = when (mode) {
        ReplayGainMode.OFF -> false
        ReplayGainMode.TRACK -> (trackGainDb ?: albumGainDb) != null
        ReplayGainMode.ALBUM -> (albumGainDb ?: trackGainDb) != null
    }

    private const val R128_TO_REPLAYGAIN_OFFSET_DB = 5.0f
}
