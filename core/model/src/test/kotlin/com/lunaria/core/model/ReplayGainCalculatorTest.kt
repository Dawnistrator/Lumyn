package com.lunaria.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayGainCalculatorTest {

    @Test
    fun parsesStandardGainAndR128Strings() {
        assertEquals(-6.42f, ReplayGainCalculator.parseGainDb("-6.42 dB")!!, 0.001f)
        assertEquals(1.25f, ReplayGainCalculator.parseGainDb("+1,25")!!, 0.001f)
        assertNull(ReplayGainCalculator.parseGainDb("  "))

        assertEquals(0.95f, ReplayGainCalculator.parsePeak("0.950000")!!, 0.0001f)
        assertNull(ReplayGainCalculator.parsePeak("0.0"))

        // R128 Q7.8 = -512 (-2.0 LUFS delta from -23) + 5.0 dB reference offset = +3.0 dB
        assertEquals(3.0f, ReplayGainCalculator.parseR128GainDb("-512")!!, 0.01f)
    }

    @Test
    fun computesEffectiveGainWithPeakClippingGuard() {
        assertNull(
            ReplayGainCalculator.computeEffectiveGainDb(
                mode = ReplayGainMode.OFF,
                trackGainDb = -4.0f,
                albumGainDb = -5.0f,
                trackPeak = 0.8f,
                albumPeak = 0.9f,
                preampDb = 2.0f,
            ),
        )

        // With preamp +5 dB and raw track gain -1 dB -> desired +4 dB, but peak 0.8 allows at most ~ +1.938 dB.
        val limited = ReplayGainCalculator.computeEffectiveGainDb(
            mode = ReplayGainMode.TRACK,
            trackGainDb = -1.0f,
            albumGainDb = -5.0f,
            trackPeak = 0.8f,
            albumPeak = 0.9f,
            preampDb = 5.0f,
        )!!
        assertEquals(1.938f, limited, 0.02f)

        // Album mode falls back cleanly to albumGainDb
        val album = ReplayGainCalculator.computeEffectiveGainDb(
            mode = ReplayGainMode.ALBUM,
            trackGainDb = -2.0f,
            albumGainDb = -5.5f,
            trackPeak = null,
            albumPeak = null,
            preampDb = 1.0f,
        )!!
        assertEquals(-4.5f, album, 0.001f)

        assertTrue(ReplayGainCalculator.hasTagForMode(ReplayGainMode.TRACK, -3.0f, null))
        assertFalse(ReplayGainCalculator.hasTagForMode(ReplayGainMode.ALBUM, null, null))
    }
}
