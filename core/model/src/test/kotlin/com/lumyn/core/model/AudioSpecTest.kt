package com.lumyn.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSpecTest {

    private fun spec(
        codec: String = "FLAC",
        bitDepth: Int = 16,
        sampleRateHz: Int = 44_100,
        lossless: Boolean = true,
    ) = AudioSpec(codec, bitDepth, sampleRateHz, bitrateKbps = 900, channelCount = 2, isLossless = lossless)

    @Test
    fun cdQualityFlacIsNotHiRes() {
        assertFalse(spec().isHiRes)
    }

    @Test
    fun twentyFourBitLosslessIsHiRes() {
        assertTrue(spec(bitDepth = 24, sampleRateHz = 48_000).isHiRes)
    }

    @Test
    fun highSampleRateLosslessIsHiRes() {
        assertTrue(spec(bitDepth = 16, sampleRateHz = 96_000).isHiRes)
    }

    @Test
    fun lossyIsNeverHiRes() {
        assertFalse(spec(codec = "MP3", bitDepth = 16, sampleRateHz = 96_000, lossless = false).isHiRes)
    }

    @Test
    fun qualitySummaryFormatsWholeAndFractionalKhz() {
        assertEquals("FLAC • 24-bit / 192 kHz", spec(bitDepth = 24, sampleRateHz = 192_000).formatQualitySummary())
        assertEquals("FLAC • 16-bit / 44.1 kHz", spec().formatQualitySummary())
    }

    @Test
    fun playbackProgressIsClampedAndSafeWithoutDuration() {
        assertEquals(0f, PlaybackState().progress, 0f)
        assertEquals(0.5f, PlaybackState(positionMs = 50, durationMs = 100).progress, 0f)
        assertEquals(1f, PlaybackState(positionMs = 500, durationMs = 100).progress, 0f)
    }
}
