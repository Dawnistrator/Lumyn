package com.lunaria.core.model

/**
 * The two honest audio modes described in the blueprint.
 * Purist bypasses the system mixer/effects; DSP keeps the 32-bit float pipeline for EQ and ReplayGain.
 */
enum class AudioOutputMode {
    DSP_FLOAT_32,
    PURIST_DIRECT_BIT_PERFECT,
}

enum class AudioOutputCategory {
    USB_DAC,
    BLUETOOTH_A2DP,
    WIRED,
    SPEAKER,
    OTHER,
}

/** What the output path actually looks like right now, shown in the Signal Path panel. */
data class AudioSignalPath(
    val sourceFormatLabel: String,
    val decoderName: String,
    val processingChain: List<String>,
    val outputDeviceName: String,
    val outputSampleRateHz: Int,
    val outputBitDepth: Int,
    val isDirectPlaybackActive: Boolean,
    val isBitPerfectActive: Boolean,
    val outputMode: AudioOutputMode,
    val outputCategory: AudioOutputCategory = AudioOutputCategory.SPEAKER,
    val outputEncodingLabel: String = "",
)

/** Device-level facts about hi-res output, evaluated once per query or route change. */
data class AudioCapabilitiesSnapshot(
    val apiLevel: Int,
    val supportsFloatOutput: Boolean,
    val supportsDirectPlayback: Boolean,
    val supportsBitPerfectUsb: Boolean,
    val activeOutputSummary: String,
    val outputCategory: AudioOutputCategory = AudioOutputCategory.SPEAKER,
    val usbDacName: String? = null,
    val hasFfmpegExtension: Boolean = false,
)
