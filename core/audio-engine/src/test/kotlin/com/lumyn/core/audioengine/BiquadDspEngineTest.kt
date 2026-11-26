package com.lumyn.core.audioengine

import com.lumyn.core.model.EqualizerSettings
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BiquadDspEngineTest {

    @Test
    fun puristBypassLeavesAudioSamplesBitExact() {
        val engine = BiquadDspEngine()
        engine.configure(sampleRateHz = 48_000, channelCount = 2)
        val gains = List(10) { if (it == 5) 9.0f else 0f }
        engine.updateSettings(
            settings = EqualizerSettings(enabled = true, preampDb = -4f, bandGainsDb = gains),
            bypassed = true,
        )
        assertFalse(engine.isProcessingActive())

        val input = floatArrayOf(0.25f, -0.5f, 0.75f, -0.125f)
        val expected = input.copyOf()
        engine.processInterleavedFloats(input, frameCount = 2)
        assertArrayEquals(expected, input, 0f)
    }

    @Test
    fun boostAt1kHzIncreasesMagnitudeResponseAndRmsEnergy() {
        val engine = BiquadDspEngine()
        val sampleRate = 48_000
        engine.configure(sampleRateHz = sampleRate, channelCount = 2)
        val gains = List(10) { index -> if (index == 5) 6.0f else 0f } // band 5 = 1 kHz
        engine.updateSettings(
            settings = EqualizerSettings(enabled = true, bandGainsDb = gains),
            bypassed = false,
        )
        assertTrue(engine.isProcessingActive())
        assertEquals(6.0, engine.computeMagnitudeResponseDb(1_000.0), 0.35)

        val frames = 2_400
        val samples = FloatArray(frames * 2)
        var inputSq = 0.0
        for (i in 0 until frames) {
            val s = (0.15 * sin(2.0 * PI * 1_000.0 * i / sampleRate)).toFloat()
            samples[i * 2] = s
            samples[i * 2 + 1] = s
            inputSq += s * s
        }
        engine.processInterleavedFloats(samples, frames)

        var outputSq = 0.0
        for (i in frames / 2 until frames) {
            val s = samples[i * 2].toDouble()
            outputSq += s * s
        }
        val inputRms = sqrt(inputSq / frames)
        val outputRms = sqrt(outputSq / (frames / 2))
        assertTrue("Expected boosted RMS ($outputRms) > input RMS ($inputRms)", outputRms > inputRms * 1.8)
    }

    @Test
    fun channelBalanceAndSoftLimiterPreventDigitalClipping() {
        val engine = BiquadDspEngine()
        engine.configure(sampleRateHz = 48_000, channelCount = 2)
        engine.updateSettings(
            settings = EqualizerSettings(
                enabled = true,
                preampDb = 12.0f,
                channelBalance = 1.0f, // Silence Left, keep Right at 1.0x
            ),
            bypassed = false,
        )

        val samples = floatArrayOf(0.8f, 0.8f, -0.9f, -0.9f)
        engine.processInterleavedFloats(samples, frameCount = 2)

        assertEquals(0f, samples[0], 1e-5f) // Left attenuated completely
        assertEquals(0f, samples[2], 1e-5f)
        assertTrue("Soft limiter kept right peak <= 1.0 (was ${samples[1]})", abs(samples[1]) <= 1.0f)
        assertTrue("Soft limiter kept right negative peak <= 1.0 (was ${samples[3]})", abs(samples[3]) <= 1.0f)
    }
}
