package com.lunaria.core.audioengine

import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessingPipeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.google.common.collect.ImmutableList
import com.lunaria.core.model.BuiltInEqPresets
import com.lunaria.core.model.EqualizerSettings
import com.lunaria.core.model.ReplayGainMode
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@UnstableApi
class LunariaDspPipelineTest {

    @Test
    fun pcm16InputConfiguresPipelineOutputTo32BitFloatAndNormalizesPassthrough() {
        val store = LunariaDspConfigStore()
        val chain = LunariaAudioProcessorChain(LunariaDspAudioProcessor(store))
        val pipeline = AudioProcessingPipeline(ImmutableList.copyOf(chain.getAudioProcessors()))

        val outFormat = pipeline.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        assertEquals(C.ENCODING_PCM_FLOAT, outFormat.encoding)
        assertEquals(48_000, outFormat.sampleRate)
        assertEquals(2, outFormat.channelCount)

        pipeline.flush()
        assertTrue(pipeline.isOperational)

        val inputShorts = shortArrayOf(16_384, -16_384, 8_192, -8_192)
        val outFloats = processShortsThroughPipeline(pipeline, inputShorts)

        assertEquals(4, outFloats.size)
        assertEquals(0.5f, outFloats[0], 1e-4f)
        assertEquals(-0.5f, outFloats[1], 1e-4f)
        assertEquals(0.25f, outFloats[2], 1e-4f)
        assertEquals(-0.25f, outFloats[3], 1e-4f)
        pipeline.reset()
    }

    @Test
    fun liveConfigStoreUpdatesApplyEqBandsPresetsReplayGainBalanceMonoAndBypass() {
        val store = LunariaDspConfigStore()
        val chain = LunariaAudioProcessorChain(LunariaDspAudioProcessor(store))
        val pipeline = AudioProcessingPipeline(ImmutableList.copyOf(chain.getAudioProcessors()))
        pipeline.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        pipeline.flush()

        // 1. 10-Band EQ (+6 dB at 1 kHz) increases steady-state RMS
        val eq1k = List(10) { if (it == 5) 6.0f else 0f }
        store.updateEqualizerSettings { it.copy(enabled = true, bandGainsDb = eq1k) }
        val sine1kShorts = generateStereoSine16Bit(sampleRate = 48_000, freqHz = 1_000.0, frames = 2_400, amp = 0.15)
        val boosted1k = processShortsThroughPipeline(pipeline, sine1kShorts)
        val inputRms = rmsOfFloats(sine1kShorts.map { it / 32_768f }.toFloatArray(), fromFrame = 1_200, ch = 2)
        val boostedRms = rmsOfFloats(boosted1k, fromFrame = 1_200, ch = 2)
        assertTrue("Expected boosted RMS ($boostedRms) > 1.8 * input ($inputRms)", boostedRms > inputRms * 1.8)

        // 2. Built-in Preset ("Bass Boost") boosts 62 Hz relative to 1 kHz / flat
        val bassBoost = BuiltInEqPresets.first { it.name == "Bass Boost" }
        store.updateEqualizerSettings {
            it.copy(
                enabled = true,
                preampDb = bassBoost.preampDb,
                bandGainsDb = bassBoost.bandGainsDb,
                activePresetName = bassBoost.name,
            )
        }
        val sine62Shorts = generateStereoSine16Bit(sampleRate = 48_000, freqHz = 62.0, frames = 4_800, amp = 0.20)
        val bassOut = processShortsThroughPipeline(pipeline, sine62Shorts)
        val bassInRms = rmsOfFloats(sine62Shorts.map { it / 32_768f }.toFloatArray(), fromFrame = 2_400, ch = 2)
        val bassOutRms = rmsOfFloats(bassOut, fromFrame = 2_400, ch = 2)
        assertTrue("Expected Bass Boost RMS ($bassOutRms) > input ($bassInRms)", bassOutRms > bassInRms * 1.1)

        // 3. Disabling 32-bit Float EQ returns to unity passthrough
        store.updateEqualizerSettings { it.copy(enabled = false) }
        val flatOut = processShortsThroughPipeline(pipeline, shortArrayOf(16_384, -16_384))
        assertEquals(0.5f, flatOut[0], 1e-4f)
        assertEquals(-0.5f, flatOut[1], 1e-4f)

        // 4. ReplayGain Track & Album modes apply respective gain even when EQ bands are off
        store.updateAll(
            settings = EqualizerSettings(enabled = false, replayGainMode = ReplayGainMode.TRACK),
            bypassed = false,
            trackReplayGainDb = -6.0f,
            albumReplayGainDb = -12.0f,
            trackReplayGainPeak = 0.9f,
            albumReplayGainPeak = 0.9f,
        )
        val rgTrackOut = processShortsThroughPipeline(pipeline, shortArrayOf(16_384, 16_384))
        assertEquals(0.2506f, rgTrackOut[0], 0.005f)

        store.updateEqualizerSettings { it.copy(replayGainMode = ReplayGainMode.ALBUM) }
        val rgAlbumOut = processShortsThroughPipeline(pipeline, shortArrayOf(16_384, 16_384))
        assertEquals(0.1256f, rgAlbumOut[0], 0.005f)

        // 5. Output & Mix: Mono downmix and L/R Balance
        store.updateAll(
            settings = EqualizerSettings(
                enabled = false,
                replayGainMode = ReplayGainMode.OFF,
                monoAudio = true,
            ),
            bypassed = false,
        )
        val monoOut = processShortsThroughPipeline(pipeline, shortArrayOf(16_384, 0))
        assertEquals(0.25f, monoOut[0], 1e-4f)
        assertEquals(0.25f, monoOut[1], 1e-4f)

        store.updateEqualizerSettings { it.copy(monoAudio = false, channelBalance = 1.0f) }
        val balanceOut = processShortsThroughPipeline(pipeline, shortArrayOf(16_384, 16_384))
        assertEquals(0f, balanceOut[0], 1e-5f)
        assertEquals(0.5f, balanceOut[1], 1e-4f)

        // 6. Purist mode bypass overrides all active EQ/mix stages cleanly
        store.updateBypassed(true)
        val bypassedOut = processShortsThroughPipeline(pipeline, shortArrayOf(16_384, -8_192))
        assertEquals(0.5f, bypassedOut[0], 1e-4f)
        assertEquals(-0.25f, bypassedOut[1], 1e-4f)

        pipeline.reset()
    }

    @Test
    fun playbackSpeedThroughCustomChainScalesOutputAndDurationInFloatMode() {
        val store = LunariaDspConfigStore()
        val sonic = SonicAudioProcessor()
        val chain = LunariaAudioProcessorChain(LunariaDspAudioProcessor(store), sonic)
        val pipeline = AudioProcessingPipeline(ImmutableList.copyOf(chain.getAudioProcessors()))

        // 1.5x playback speed
        chain.applyPlaybackParameters(PlaybackParameters(1.5f))
        val outFormat = pipeline.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        assertEquals(C.ENCODING_PCM_FLOAT, outFormat.encoding)
        pipeline.flush()
        assertTrue(sonic.isActive())

        val inputShorts = generateStereoSine16Bit(sampleRate = 48_000, freqHz = 440.0, frames = 48_000, amp = 0.25)
        val fastFloats = drainFullStream(pipeline, inputShorts)
        val fastFrames = fastFloats.size / 2
        assertTrue("Expected 1.5x speed frame count (~32,000), got $fastFrames", fastFrames in 28_000..36_000)
        assertTrue("Expected finite audio samples", fastFloats.all { it.isFinite() && abs(it) <= 1.5f })
        assertEquals(1_500_000.0, chain.getMediaDuration(1_000_000L).toDouble(), 150_000.0)

        // 0.5x playback speed
        chain.applyPlaybackParameters(PlaybackParameters(0.5f))
        pipeline.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        pipeline.flush()
        assertTrue(sonic.isActive())

        val slowFloats = drainFullStream(pipeline, inputShorts)
        val slowFrames = slowFloats.size / 2
        assertTrue("Expected 0.5x speed frame count (~96,000), got $slowFrames", slowFrames in 84_000..108_000)
        assertTrue("Expected finite audio samples", slowFloats.all { it.isFinite() && abs(it) <= 1.5f })

        pipeline.reset()
    }

    @Test
    fun resettingFirstPlayerDoesNotClobberSharedConfigStoreForSecondPlayer() {
        val store = LunariaDspConfigStore()
        store.updateEqualizerSettings { it.copy(enabled = true, preampDb = -6.0f) }

        val pipeline1 = AudioProcessingPipeline(
            ImmutableList.copyOf(LunariaAudioProcessorChain(LunariaDspAudioProcessor(store)).getAudioProcessors()),
        )
        pipeline1.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        pipeline1.flush()
        pipeline1.reset()

        val pipeline2 = AudioProcessingPipeline(
            ImmutableList.copyOf(LunariaAudioProcessorChain(LunariaDspAudioProcessor(store)).getAudioProcessors()),
        )
        pipeline2.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        pipeline2.flush()

        val out = processShortsThroughPipeline(pipeline2, shortArrayOf(16_384, 16_384))
        assertEquals(0.2506f, out[0], 0.005f)
        pipeline2.reset()
    }

    private fun processShortsThroughPipeline(
        pipeline: AudioProcessingPipeline,
        samples: ShortArray,
    ): FloatArray {
        val inBuf = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder())
        samples.forEach { inBuf.putShort(it) }
        inBuf.flip()

        pipeline.queueInput(inBuf)
        val floatOut = ByteArrayOutputStream()
        while (true) {
            val out = pipeline.getOutput()
            if (!out.hasRemaining()) break
            val bytes = ByteArray(out.remaining())
            out.get(bytes)
            floatOut.write(bytes)
        }
        val raw = floatOut.toByteArray()
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.nativeOrder())
        return FloatArray(raw.size / 4) { buf.getFloat() }
    }

    private fun drainFullStream(
        pipeline: AudioProcessingPipeline,
        samples: ShortArray,
        chunkSamples: Int = 2_048,
    ): FloatArray {
        val outBytes = ByteArrayOutputStream()
        var offset = 0
        while (offset < samples.size) {
            val count = minOf(chunkSamples, samples.size - offset)
            val inBuf = ByteBuffer.allocateDirect(count * 2).order(ByteOrder.nativeOrder())
            for (i in 0 until count) {
                inBuf.putShort(samples[offset + i])
            }
            inBuf.flip()
            pipeline.queueInput(inBuf)
            drainAvailable(pipeline, outBytes)
            offset += count
        }
        pipeline.queueEndOfStream()
        var guard = 0
        while (!pipeline.isEnded && guard < 1_000) {
            val drained = drainAvailable(pipeline, outBytes)
            if (drained == 0) break
            guard++
        }
        val raw = outBytes.toByteArray()
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.nativeOrder())
        return FloatArray(raw.size / 4) { buf.getFloat() }
    }

    private fun drainAvailable(pipeline: AudioProcessingPipeline, outBytes: ByteArrayOutputStream): Int {
        var total = 0
        while (true) {
            val out = pipeline.getOutput()
            if (!out.hasRemaining()) break
            val chunk = ByteArray(out.remaining())
            out.get(chunk)
            outBytes.write(chunk)
            total += chunk.size
        }
        return total
    }

    private fun generateStereoSine16Bit(
        sampleRate: Int,
        freqHz: Double,
        frames: Int,
        amp: Double,
    ): ShortArray {
        val out = ShortArray(frames * 2)
        for (i in 0 until frames) {
            val v = (amp * sin(2.0 * PI * freqHz * i / sampleRate) * 32_767.0)
                .toInt()
                .coerceIn(-32_768, 32_767)
                .toShort()
            out[i * 2] = v
            out[i * 2 + 1] = v
        }
        return out
    }

    private fun rmsOfFloats(samples: FloatArray, fromFrame: Int, ch: Int): Double {
        var sumSq = 0.0
        var count = 0
        for (i in fromFrame * ch until samples.size step ch) {
            val s = samples[i].toDouble()
            sumSq += s * s
            count++
        }
        return sqrt(sumSq / count.coerceAtLeast(1))
    }
}
