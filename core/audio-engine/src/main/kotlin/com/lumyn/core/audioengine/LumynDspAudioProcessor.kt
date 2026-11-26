package com.lumyn.core.audioengine

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.lumyn.core.model.EqualizerSettings
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

data class DspConfigSnapshot(
    val version: Long = 1L,
    val settings: EqualizerSettings = EqualizerSettings(),
    val bypassed: Boolean = false,
    val trackReplayGainDb: Float? = null,
    val albumReplayGainDb: Float? = null,
    val trackReplayGainPeak: Float? = null,
    val albumReplayGainPeak: Float? = null,
)

/**
 * Thread-safe, application-scoped store holding the active DSP configuration and current track
 * ReplayGain metadata. Read lock-free by per-player [LumynDspAudioProcessor] instances.
 */
@Singleton
class LumynDspConfigStore @Inject constructor() {

    private val versionCounter = AtomicLong(1L)
    private val state = AtomicReference(DspConfigSnapshot())

    val snapshot: DspConfigSnapshot
        get() = state.get()

    fun updateAll(
        settings: EqualizerSettings,
        bypassed: Boolean,
        trackReplayGainDb: Float? = null,
        albumReplayGainDb: Float? = null,
        trackReplayGainPeak: Float? = null,
        albumReplayGainPeak: Float? = null,
    ) {
        val nextVersion = versionCounter.incrementAndGet()
        state.set(
            DspConfigSnapshot(
                version = nextVersion,
                settings = settings,
                bypassed = bypassed,
                trackReplayGainDb = trackReplayGainDb,
                albumReplayGainDb = albumReplayGainDb,
                trackReplayGainPeak = trackReplayGainPeak,
                albumReplayGainPeak = albumReplayGainPeak,
            ),
        )
    }

    fun updateEqualizerSettings(transform: (EqualizerSettings) -> EqualizerSettings) {
        state.updateAndGet { prev ->
            prev.copy(
                version = versionCounter.incrementAndGet(),
                settings = transform(prev.settings),
            )
        }
    }

    fun updateBypassed(bypassed: Boolean) {
        state.updateAndGet { prev ->
            if (prev.bypassed == bypassed) {
                prev
            } else {
                prev.copy(
                    version = versionCounter.incrementAndGet(),
                    bypassed = bypassed,
                )
            }
        }
    }
}

/**
 * Real-time Media3 [AudioProcessor] executing inside `DefaultAudioSink` when running in
 * DSP 32-bit Float mode. Converts incoming PCM to 32-bit float (`C.ENCODING_PCM_FLOAT`)
 * and applies live atomic EQ, ReplayGain, L/R balance, and mono downmix updates.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class LumynDspAudioProcessor(
    private val configStore: LumynDspConfigStore = LumynDspConfigStore(),
) : BaseAudioProcessor() {

    private val engine = BiquadDspEngine()
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
        configStore.updateAll(
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
        return AudioProcessor.AudioFormat(
            inputAudioFormat.sampleRate,
            inputAudioFormat.channelCount,
            C.ENCODING_PCM_FLOAT,
        )
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        syncConfigIfNeeded()

        if (inputAudioFormat == AudioProcessor.AudioFormat.NOT_SET) {
            inputBuffer.position(inputBuffer.limit())
            return
        }

        if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT &&
            !engine.isProcessingActive() &&
            inputBuffer.order() == ByteOrder.nativeOrder()
        ) {
            val out = replaceOutputBuffer(remaining)
            out.order(ByteOrder.nativeOrder())
            out.put(inputBuffer)
            out.flip()
            return
        }

        val bytesPerSample = when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_FLOAT -> 4
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT -> 4
            else -> {
                inputBuffer.position(inputBuffer.limit())
                return
            }
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

        if (inputBuffer.hasRemaining()) {
            inputBuffer.position(inputBuffer.limit())
        }

        if (engine.isProcessingActive()) {
            engine.processInterleavedFloats(scratchSamples, frameCount)
        }

        val outBytes = usedSamples * 4
        val outBuffer = replaceOutputBuffer(outBytes)
        outBuffer.order(ByteOrder.nativeOrder())
        for (i in 0 until usedSamples) {
            outBuffer.putFloat(scratchSamples[i])
        }
        outBuffer.flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        if (inputAudioFormat != AudioProcessor.AudioFormat.NOT_SET) {
            engine.configure(
                sampleRateHz = inputAudioFormat.sampleRate,
                channelCount = inputAudioFormat.channelCount,
            )
        }
        engine.resetState()
        appliedVersion = 0L
        syncConfigIfNeeded()
    }

    override fun onReset() {
        engine.resetState()
        appliedVersion = 0L
    }

    private fun syncConfigIfNeeded() {
        val snap = configStore.snapshot
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
