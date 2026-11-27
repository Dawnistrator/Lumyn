package com.lunaria.core.audioengine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioCapabilitiesInspectorTest {

    private fun eval(api: Int, flag: Int, usb: Boolean) =
        AudioCapabilitiesInspector.evaluateCapabilities(api, flag, usb, "System Output")

    @Test
    fun floatOutputIsAlwaysAvailable() {
        assertTrue(eval(26, 0, false).supportsFloatOutput)
    }

    @Test
    fun directPlaybackNeedsApi33AndANonZeroFlag() {
        assertFalse(eval(32, 4, false).supportsDirectPlayback)
        assertFalse(eval(33, 0, false).supportsDirectPlayback)
        assertTrue(eval(33, 4, false).supportsDirectPlayback)
    }

    @Test
    fun bitPerfectNeedsApi34AndUsbMixerSupport() {
        assertFalse(eval(33, 0, true).supportsBitPerfectUsb)
        assertFalse(eval(34, 0, false).supportsBitPerfectUsb)
        assertTrue(eval(34, 0, true).supportsBitPerfectUsb)
    }
}
