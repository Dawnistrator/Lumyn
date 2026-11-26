package com.lumyn.core.model

/** Technical description of an audio file as read from its container/tags. */
data class AudioSpec(
    val codec: String,
    val bitDepth: Int,
    val sampleRateHz: Int,
    val bitrateKbps: Int,
    val channelCount: Int,
    val isLossless: Boolean,
) {
    /** Hi-Res means lossless with more than CD resolution (24-bit or above 48 kHz). */
    val isHiRes: Boolean
        get() = isLossless && (bitDepth >= 24 || sampleRateHz > 48_000)

    /** Compact label such as `FLAC • 24-bit / 192 kHz`. */
    fun formatQualitySummary(): String {
        val khz = sampleRateHz / 1000.0
        val khzText = if (sampleRateHz % 1000 == 0) "${sampleRateHz / 1000}" else "%.1f".format(khz)
        return "$codec • $bitDepth-bit / $khzText kHz"
    }
}
