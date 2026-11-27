package com.lunaria.core.audioengine

import com.lunaria.core.model.AudioCapabilitiesSnapshot
import com.lunaria.core.model.AudioOutputCategory
import com.lunaria.core.model.AudioOutputMode
import com.lunaria.core.model.AudioSignalPath
import com.lunaria.core.model.EqualizerSettings
import com.lunaria.core.model.ReplayGainCalculator
import com.lunaria.core.model.ReplayGainMode
import com.lunaria.core.model.Track
import java.util.Locale
import kotlin.math.abs

data class RuntimeAudioTelemetry(
    val decoderName: String? = null,
    val trackEncoding: Int = 0,
    val trackSampleRateHz: Int = 0,
    val isOffload: Boolean = false,
)

/**
 * Pure Kotlin builder for Lunaria's honest Audio Signal Path panel.
 * Never claims Bluetooth A2DP is bit-perfect and distinguishes DSP 32-bit float from Direct/USB bypass.
 */
object AudioSignalPathBuilder {

    fun build(
        track: Track?,
        mode: AudioOutputMode,
        telemetry: RuntimeAudioTelemetry,
        capabilities: AudioCapabilitiesSnapshot,
        bitPerfectStatus: BitPerfectStatus,
        equalizerSettings: EqualizerSettings = EqualizerSettings(),
    ): AudioSignalPath? {
        if (track == null) return null

        val spec = track.audioSpec
        val sourceLabel = buildString {
            append(spec.formatQualitySummary())
            if (spec.bitrateKbps > 0) {
                append(" • ")
                append(spec.bitrateKbps)
                append(" kbps")
            }
        }

        val decoderLabel = formatDecoderName(telemetry.decoderName, spec.codec, capabilities.hasFfmpegExtension)
        val isBluetooth = capabilities.outputCategory == AudioOutputCategory.BLUETOOTH_A2DP
        val isBitPerfect = !isBluetooth &&
            mode == AudioOutputMode.PURIST_DIRECT_BIT_PERFECT &&
            bitPerfectStatus.isBitPerfectActive
        val isDirect = !isBluetooth &&
            mode == AudioOutputMode.PURIST_DIRECT_BIT_PERFECT &&
            !isBitPerfect &&
            capabilities.supportsDirectPlayback &&
            (telemetry.trackEncoding == BitPerfectMixerSelector.ENCODING_PCM_24BIT_PACKED ||
                telemetry.trackEncoding == BitPerfectMixerSelector.ENCODING_PCM_32BIT ||
                spec.bitDepth >= 24)

        val encoding = resolveEffectiveEncoding(mode, telemetry.trackEncoding, isBitPerfect, bitPerfectStatus)
        val encodingLabel = BitPerfectMixerSelector.encodingLabel(encoding)
        val outputRate = bitPerfectStatus.sampleRateHz
            ?: telemetry.trackSampleRateHz.takeIf { it > 0 }
            ?: when {
                isDirect || isBitPerfect -> spec.sampleRateHz
                else -> 48_000
            }
        val outputBits = bitPerfectStatus.bitDepth ?: BitPerfectMixerSelector.encodingBitDepth(encoding)

        val dspSummary = describeDspStages(equalizerSettings, track)

        val chain = when {
            isBluetooth -> listOfNotNull(
                if (mode == AudioOutputMode.DSP_FLOAT_32) "32-bit Float DSP" else "PCM AudioSink",
                if (mode == AudioOutputMode.DSP_FLOAT_32) dspSummary else null,
                "Bluetooth A2DP • System-managed",
            )

            isBitPerfect -> listOf(
                "Purist Native PCM Bypass (Bit-Perfect USB)",
                "EQ, ReplayGain & system mixer bypassed",
            )

            isDirect -> listOf(
                "Direct PCM Bypass (No Mixer)",
                "EQ & DSP bypassed",
            )

            mode == AudioOutputMode.PURIST_DIRECT_BIT_PERFECT -> listOf(
                "Purist fallback via System Mixer (Direct unavailable)",
                "EQ & ReplayGain bypassed",
            )

            else -> listOf(
                "32-bit Float AudioSink",
                dspSummary,
            )
        }

        return AudioSignalPath(
            sourceFormatLabel = sourceLabel,
            decoderName = decoderLabel,
            processingChain = chain,
            outputDeviceName = capabilities.activeOutputSummary,
            outputSampleRateHz = outputRate,
            outputBitDepth = outputBits,
            isDirectPlaybackActive = isDirect,
            isBitPerfectActive = isBitPerfect,
            outputMode = mode,
            outputCategory = capabilities.outputCategory,
            outputEncodingLabel = encodingLabel,
        )
    }

    private fun describeDspStages(eq: EqualizerSettings, track: Track): String {
        val activeStages = buildList {
            if (eq.enabled) {
                val activeFilters = eq.parametricFilters.count { it.enabled && abs(it.gainDb) >= 0.05 }
                if (activeFilters > 0) {
                    add("AutoEQ (${eq.activePresetName}, $activeFilters filters)")
                } else {
                    add("10-band EQ (${eq.activePresetName})")
                }
            }
            if (eq.replayGainMode != ReplayGainMode.OFF) {
                val modeName = when (eq.replayGainMode) {
                    ReplayGainMode.TRACK -> "Track"
                    ReplayGainMode.ALBUM -> "Album"
                    ReplayGainMode.OFF -> ""
                }
                val hasTag = ReplayGainCalculator.hasTagForMode(
                    mode = eq.replayGainMode,
                    trackGainDb = track.replayGainTrackDb,
                    albumGainDb = track.replayGainAlbumDb,
                )
                val effectiveDb = ReplayGainCalculator.computeEffectiveGainDb(
                    mode = eq.replayGainMode,
                    trackGainDb = track.replayGainTrackDb,
                    albumGainDb = track.replayGainAlbumDb,
                    trackPeak = track.replayGainTrackPeak,
                    albumPeak = track.replayGainAlbumPeak,
                    preampDb = eq.replayGainPreampDb,
                )
                val suffix = when {
                    hasTag && effectiveDb != null ->
                        String.format(Locale.US, " %+.1f dB", effectiveDb)
                    abs(eq.replayGainPreampDb) >= 0.05f ->
                        String.format(Locale.US, " preamp %+.1f dB", eq.replayGainPreampDb)
                    else -> " • no tag"
                }
                add("ReplayGain ($modeName$suffix)")
            }
            if (eq.monoAudio) add("Mono")
            if (abs(eq.channelBalance) >= 0.01f) add("L/R Balance")
            if (eq.crossfadeDurationSec > 0) add("Crossfade (${eq.crossfadeDurationSec}s)")
        }
        return if (activeStages.isEmpty()) {
            "Flat (no active DSP filters)"
        } else {
            activeStages.joinToString(" • ")
        }
    }

    private fun resolveEffectiveEncoding(
        mode: AudioOutputMode,
        trackEncoding: Int,
        isBitPerfect: Boolean,
        bitPerfectStatus: BitPerfectStatus,
    ): Int {
        if (isBitPerfect && bitPerfectStatus.bitDepth != null) {
            return when (bitPerfectStatus.bitDepth) {
                24 -> BitPerfectMixerSelector.ENCODING_PCM_24BIT_PACKED
                32 -> BitPerfectMixerSelector.ENCODING_PCM_32BIT
                else -> BitPerfectMixerSelector.ENCODING_PCM_16BIT
            }
        }
        if (trackEncoding != 0) return trackEncoding
        return if (mode == AudioOutputMode.DSP_FLOAT_32) {
            BitPerfectMixerSelector.ENCODING_PCM_FLOAT
        } else {
            BitPerfectMixerSelector.ENCODING_PCM_16BIT
        }
    }

    internal fun formatDecoderName(
        rawDecoderName: String?,
        codec: String,
        hasFfmpeg: Boolean,
    ): String {
        val raw = rawDecoderName?.trim().orEmpty()
        return when {
            raw.contains("ffmpeg", ignoreCase = true) || raw.contains("Ffmpeg", ignoreCase = false) ->
                "FFmpeg Software Decoder ($codec)"

            raw.contains("flac", ignoreCase = true) ->
                "MediaCodec FLAC ($raw)"

            raw.isNotEmpty() ->
                "Android MediaCodec ($raw)"

            hasFfmpeg && codec.equals("FLAC", ignoreCase = true) ->
                "FFmpeg / Media3 Audio Decoder ($codec)"

            else ->
                "Media3 Audio Decoder ($codec)"
        }
    }
}
