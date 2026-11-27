package com.lunaria.core.audioengine

import com.lunaria.core.model.AudioOutputCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BitPerfectMixerSelectorTest {

    @Test
    fun prefersExactSampleRateAnd24BitForHiResTrack() {
        val candidates = listOf(
            MixerCandidate(BitPerfectMixerSelector.ENCODING_PCM_16BIT, 48_000, 12, true),
            MixerCandidate(BitPerfectMixerSelector.ENCODING_PCM_16BIT, 96_000, 12, true),
            MixerCandidate(BitPerfectMixerSelector.ENCODING_PCM_24BIT_PACKED, 96_000, 12, true),
            MixerCandidate(BitPerfectMixerSelector.ENCODING_PCM_32BIT, 192_000, 12, true),
        )
        val chosen = BitPerfectMixerSelector.selectBestBitPerfectCandidate(
            candidates = candidates,
            targetSampleRateHz = 96_000,
            targetBitDepth = 24,
        )
        assertNotNull(chosen)
        assertEquals(96_000, chosen!!.sampleRateHz)
        assertEquals(BitPerfectMixerSelector.ENCODING_PCM_24BIT_PACKED, chosen.encoding)
    }

    @Test
    fun fallsBackToNextHigherRateWhenExactUnsupported() {
        val candidates = listOf(
            MixerCandidate(BitPerfectMixerSelector.ENCODING_PCM_24BIT_PACKED, 48_000, 12, true),
            MixerCandidate(BitPerfectMixerSelector.ENCODING_PCM_24BIT_PACKED, 88_200, 12, true),
            MixerCandidate(BitPerfectMixerSelector.ENCODING_PCM_32BIT, 192_000, 12, false),
        )
        val chosen = BitPerfectMixerSelector.selectBestBitPerfectCandidate(
            candidates = candidates,
            targetSampleRateHz = 44_100,
            targetBitDepth = 16,
        )
        assertNotNull(chosen)
        assertEquals(48_000, chosen!!.sampleRateHz)
    }

    @Test
    fun returnsNullWhenNoBitPerfectAttributesExist() {
        val candidates = listOf(
            MixerCandidate(BitPerfectMixerSelector.ENCODING_PCM_16BIT, 48_000, 12, isBitPerfect = false),
        )
        assertNull(BitPerfectMixerSelector.selectBestBitPerfectCandidate(candidates, 48_000, 16))
    }

    @Test
    fun classifiesUsbAndBluetoothOutputsHonestly() {
        val usb = BitPerfectMixerSelector.describeAudioDeviceType(11, "Moondrop Dawn Pro")
        assertTrue(usb.isUsbDac)
        assertEquals(AudioOutputCategory.USB_DAC, usb.category)
        assertEquals("USB DAC (Moondrop Dawn Pro)", usb.label)

        val bt = BitPerfectMixerSelector.describeAudioDeviceType(8, "Sony WF-1000XM5")
        assertFalse(bt.isUsbDac)
        assertEquals(AudioOutputCategory.BLUETOOTH_A2DP, bt.category)
        assertEquals("Bluetooth (Sony WF-1000XM5)", bt.label)
    }
}
