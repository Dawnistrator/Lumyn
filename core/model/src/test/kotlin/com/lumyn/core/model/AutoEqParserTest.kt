package com.lumyn.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoEqParserTest {

    @Test
    fun parsesParametricAutoEqWithPreampAndShelvingFilters() {
        val sample = """
            Preamp: -6.2 dB
            Filter 1: ON LSC Fc 105 Hz Gain 4.8 dB Q 0.70
            Filter 2: ON PK Fc 1950 Hz Gain -3.4 dB Q 1.65
            Filter 3: OFF PK Fc 5000 Hz Gain 2.0 dB Q 1.00
            Filter 4: ON HSC Fc 10000 Hz Gain -1.5 dB Q 0.71
        """.trimIndent()

        val parsed = AutoEqParser.parse(sample, fallbackName = "Sennheiser_HD600.txt")
        assertNotNull(parsed)
        assertEquals("Sennheiser_HD600", parsed!!.name)
        assertEquals(-6.2f, parsed.preampDb, 0.01f)
        assertEquals(4, parsed.parametricFilters.size)

        val lsc = parsed.parametricFilters[0]
        assertTrue(lsc.enabled)
        assertEquals(ParametricFilterType.LOW_SHELF, lsc.type)
        assertEquals(105.0, lsc.frequencyHz, 0.01)
        assertEquals(4.8, lsc.gainDb, 0.01)
        assertEquals(0.70, lsc.qFactor, 0.01)

        assertEquals(false, parsed.parametricFilters[2].enabled)
        assertEquals(ParametricFilterType.HIGH_SHELF, parsed.parametricFilters[3].type)
    }

    @Test
    fun parsesGraphicEqInto10StandardBandsAndCalculatesSafePreamp() {
        val sample = "GraphicEQ: 31 4.0; 125 2.0; 1000 -1.0; 8000 3.0; 16000 3.0"
        val parsed = AutoEqParser.parse(sample, fallbackName = "Warm_V")
        assertNotNull(parsed)
        assertEquals(10, parsed!!.bandGainsDb.size)
        assertEquals(4.0f, parsed.bandGainsDb[0], 0.01f) // 31 Hz
        assertEquals(2.0f, parsed.bandGainsDb[2], 0.01f) // 125 Hz
        assertEquals(-1.0f, parsed.bandGainsDb[5], 0.01f) // 1000 Hz
        assertTrue(parsed.preampDb <= -3.9f)
    }

    @Test
    fun returnsNullOnUnrecognizedContent() {
        assertNull(AutoEqParser.parse("Just some random notes\nNothing matching"))
        assertNull(AutoEqParser.parse("   "))
    }
}
