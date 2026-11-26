package com.lumyn.core.audioengine

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.lumyn.core.model.EqualizerSettings
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real-time Media3 [AudioProcessor] executing inside `DefaultAudioSink` when running in
 * DSP 32-bit Float mode. Supports live atomic parameter updates without dropping playback.
 */
@Singleton
@androidx.annotation.OptIn(UnstableApi::class)
class LumynDspAudioProcessor @Inject constructor() : BaseAudioProcessor() {

    private data class DspConfigSnapshot(
        val version: Long,
        val settings: EqualizerSettings,
        val bypassed: Boolean,
        val trackReplayGainDb: Float?,
        val albumReplayGainDb: Float?,
        val trackReplayGainPeak: Float?,
        val albumReplayGainPeak: Float?,
    )

    private val engine = BiquadDspEngine()
    private val versionCounter = AtomicLong(1L)

    @Volatile
    private var publishedSnapshot = DspConfigSnapshot(
        version = 1L,
        settings = EqualizerSettings(),
        bypassed = false,
        trackReplayGainDb = null,
        albumReplayGainDb = null,
        trackReplayGainPeak = null,
        albumReplayGainPeak = null,
    )
    private var appliedVersion = 0L
    private var scratchSamples = FloatArray(0)

    fun updateSettings(
        settings: EqualizerSettings,
        bypassed: Boolean,
        trackReplayGainDb: Float? = null,
        albumReplayGainDb: Float? = null,
        trackReplayGainPeak: Float? = null,
        albumReplayGainPeak: Float? = null,
    ) {
        val nextVersion = versionCounter.incrementAndGet()
        publishedSnapshot = DspConfigSnapshot(
            version = nextVersion,
            settings = settings,
            bypassed = bypassed,
            trackReplayGainDb = trackReplayGainDb,
            albumReplayGainDb = albumReplayGainDb,
            trackReplayGainPeak = trackReplayGainPeak,
            albumReplayGainPeak = albumReplayGainPeak,
        )
    }

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat,
    ): AudioProcessor.AudioFormat {
        val encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_FLOAT &&
            encoding != C.ENCODING_PCM_16BIT &&
            encoding != C.ENCODING_PCM_24BIT &&
            encoding != C.ENCODING_PCM_32BIT
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        engine.configure(
            sampleRateHz = inputAudioFormat.sampleRate,
            channelCount = inputAudioFormat.channelCount,
        )
        syncConfigIfNeeded()
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        syncConfigIfNeeded()

        if (!engine.isProcessingActive() || inputAudioFormat == AudioProcessor.AudioFormat.NOT_SET) {
            val out = replaceOutputBuffer(remaining)
            out.order(inputBuffer.order())
            out.put(inputBuffer)
            out.flip()
            return
        }

        val bytesPerSample = when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_FLOAT -> 4
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT -> 4
            else -> return
        }
        val channelCount = inputAudioFormat.channelCount.coerceAtLeast(1)
        val totalSamples = remaining / bytesPerSample
        val frameCount = totalSamples / channelCount
        if (frameCount <= 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }

        val usedSamples = frameCount * channelCount
        if (scratchSamples.size < usedSamples) {
            scratchSamples = FloatArray(usedSamples)
        }

        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_FLOAT -> {
                for (i in 0 until usedSamples) {
                    scratchSamples[i] = inputBuffer.getFloat()
                }
            }

            C.ENCODING_PCM_16BIT -> {
                for (i in 0 until usedSamples) {
                    scratchSamples[i] = inputBuffer.getShort() / 32_768f
                }
            }

            C.ENCODING_PCM_24BIT -> {
                for (i in 0 until usedSamples) {
                    val b0 = inputBuffer.get().toInt() and 0xFF
                    val b1 = inputBuffer.get().toInt() and 0xFF
                    val b2 = inputBuffer.get().toInt()
                    val int24 = b0 or (b1 shl 8) or (b2 shl 16)
                    scratchSamples[i] = int24 / 8_388_608f
                }
            }

            C.ENCODING_PCM_32BIT -> {
                for (i in 0 until usedSamples) {
                    scratchSamples[i] = (inputBuffer.getInt().toDouble() / 2_147_483_648.0).toFloat()
                }
            }
        }

        engine.processInterleavedFloats(scratchSamples, frameCount)

        val outBytes = usedSamples * bytesPerSample
        val outBuffer = replaceOutputBuffer(outBytes)
        outBuffer.order(inputBuffer.order())

        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_FLOAT -> {
                for (i in 0 until usedSamples) {
                    outBuffer.putFloat(scratchSamples[i])
                }
            }

            C.ENCODING_PCM_16BIT -> {
                for (i in 0 until usedSamples) {
                    val s = (scratchSamples[i].coerceIn(-1f, 1f) * 32_767f)
                        .toInt()
                        .coerceIn(-32_768, 32_767)
                        .toShort()
                    outBuffer.putShort(s)
                }
            }

            C.ENCODING_PCM_24BIT -> {
                for (i in 0 until usedSamples) {
                    val v = (scratchSamples[i].coerceIn(-1f, 1f) * 8_388_607f)
                        .toInt()
                        .coerceIn(-8_388_608, 8_388_607)
                    outBuffer.put((v and 0xFF).toByte())
                    outBuffer.put(((v shr 8) and 0xFF).toByte())
                    outBuffer.put(((v shr 16) and 0xFF).toByte())
                }
            }

            C.ENCODING_PCM_32BIT -> {
                for (i in 0 until usedSamples) {
                    val v = (scratchSamples[i].coerceIn(-1f, 1f).toDouble() * 2_147_483_647.0)
                        .toLong()
                        .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
                        .toInt()
                    outBuffer.putInt(v)
                }
            }
        }

        outBuffer.flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        engine.resetState()
    }

    override fun onReset() {
        engine.resetState()
    }

    private fun syncConfigIfNeeded() {
        val snap = publishedSnapshot
        if (snap.version != appliedVersion) {
            appliedVersion = snap.version
            engine.updateSettings(
                settings = snap.settings,
                bypassed = snap.bypassed,
                trackReplayGainDb = snap.trackReplayGainDb,
                albumReplayGainDb = snap.albumReplayGainDb,
                trackReplayGainPeak = snap.trackReplayGainPeak,
                albumReplayGainPeak = snap.albumReplayGainPeak,
            )
        }
    }
}
