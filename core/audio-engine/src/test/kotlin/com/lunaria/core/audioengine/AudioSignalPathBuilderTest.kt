package com.lunaria.core.audioengine

import com.lunaria.core.model.AudioCapabilitiesSnapshot
import com.lunaria.core.model.AudioOutputCategory
import com.lunaria.core.model.AudioOutputMode
import com.lunaria.core.model.AudioSpec
import com.lunaria.core.model.EqualizerSettings
import com.lunaria.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSignalPathBuilderTest {

    private fun hiResTrack() = Track(
        id = 1L,
        contentUri = "content://media/external/audio/media/1",
        title = "Studio Take",
        artist = "Artist",
        album = "Master",
        albumArtist = "Artist",
        albumId = 10L,
        durationMs = 240_000L,
        trackNumber = 1,
        discNumber = 1,
        year = 2025,
        genre = "Classical",
        folderPath = "Music/HiRes",
        fileName = "01.flac",
        mimeType = "audio/flac",
        audioSpec = AudioSpec("FLAC", 24, 192_000, 4620, 2, true),
        artworkUri = null,
        isFavorite = false,
        dateAddedSeconds = 100L,
    )

    @Test
    fun reportsBitPerfectUsbWhenPuristAndUsbMixerActive() {
        val path = AudioSignalPathBuilder.build(
            track = hiResTrack(),
            mode = AudioOutputMode.PURIST_DIRECT_BIT_PERFECT,
            telemetry = RuntimeAudioTelemetry(decoderName = "c2.android.flac.decoder", trackEncoding = 21, trackSampleRateHz = 192_000),
            capabilities = AudioCapabilitiesSnapshot(
                apiLevel = 34,
                supportsFloatOutput = true,
                supportsDirectPlayback = true,
                supportsBitPerfectUsb = true,
                activeOutputSummary = "USB DAC (CS43131)",
                outputCategory = AudioOutputCategory.USB_DAC,
                usbDacName = "CS43131",
                hasFfmpegExtension = true,
            ),
            bitPerfectStatus = BitPerfectStatus(isBitPerfectActive = true, sampleRateHz = 192_000, bitDepth = 24),
        )
        assertNotNull(path)
        assertTrue(path!!.isBitPerfectActive)
        assertFalse(path.isDirectPlaybackActive)
        assertEquals(192_000, path.outputSampleRateHz)
        assertEquals(24, path.outputBitDepth)
        assertTrue(path.processingChain.any { it.contains("Bit-Perfect", ignoreCase = true) })
    }

    @Test
    fun bluetoothNeverClaimsBitPerfectEvenInPuristMode() {
        val path = AudioSignalPathBuilder.build(
            track = hiResTrack(),
            mode = AudioOutputMode.PURIST_DIRECT_BIT_PERFECT,
            telemetry = RuntimeAudioTelemetry(decoderName = "FfmpegAudioDecoder"),
            capabilities = AudioCapabilitiesSnapshot(
                apiLevel = 35,
                supportsFloatOutput = true,
                supportsDirectPlayback = false,
                supportsBitPerfectUsb = false,
                activeOutputSummary = "Bluetooth (LDAC)",
                outputCategory = AudioOutputCategory.BLUETOOTH_A2DP,
                hasFfmpegExtension = true,
            ),
            bitPerfectStatus = BitPerfectStatus(),
        )
        assertNotNull(path)
        assertFalse(path!!.isBitPerfectActive)
        assertFalse(path.isDirectPlaybackActive)
        assertTrue(path.decoderName.contains("FFmpeg"))
        assertTrue(path.processingChain.any { it.contains("Bluetooth A2DP") })
    }

    @Test
    fun dspModeReports32BitFloatSink() {
        val path = AudioSignalPathBuilder.build(
            track = hiResTrack(),
            mode = AudioOutputMode.DSP_FLOAT_32,
            telemetry = RuntimeAudioTelemetry(
                decoderName = "FfmpegAudioDecoder",
                trackEncoding = BitPerfectMixerSelector.ENCODING_PCM_FLOAT,
                trackSampleRateHz = 48_000,
            ),
            capabilities = AudioCapabilitiesSnapshot(
                apiLevel = 26,
                supportsFloatOutput = true,
                supportsDirectPlayback = false,
                supportsBitPerfectUsb = false,
                activeOutputSummary = "Device Speaker",
                outputCategory = AudioOutputCategory.SPEAKER,
                hasFfmpegExtension = true,
            ),
            bitPerfectStatus = BitPerfectStatus(),
            equalizerSettings = EqualizerSettings(enabled = true, activePresetName = "Studio Reference"),
        )
        assertNotNull(path)
        assertFalse(path!!.isBitPerfectActive)
        assertEquals(32, path.outputBitDepth)
        assertTrue(path.processingChain.first().contains("32-bit Float"))
        assertTrue(path.processingChain.any { it.contains("Studio Reference") })
    }
}
