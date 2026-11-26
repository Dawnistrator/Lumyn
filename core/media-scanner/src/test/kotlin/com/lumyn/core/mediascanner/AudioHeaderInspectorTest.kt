package com.lumyn.core.mediascanner

import com.lumyn.core.mediascanner.metadata.AudioHeaderInspector
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioHeaderInspectorTest {

    private fun buildFlacHeader(sampleRate: Int, channels: Int, bitDepth: Int): ByteArray {
        val bytes = ByteArray(42)
        "fLaC".encodeToByteArray().copyInto(bytes, 0)
        bytes[4] = 0x00 // STREAMINFO block (type 0)
        bytes[7] = 34 // STREAMINFO length

        val sr = sampleRate and 0xFFFFF
        val ch = (channels - 1) and 0x07
        val bps = (bitDepth - 1) and 0x1F
        bytes[18] = ((sr ushr 12) and 0xFF).toByte()
        bytes[19] = ((sr ushr 4) and 0xFF).toByte()
        bytes[20] = (((sr and 0x0F) shl 4) or (ch shl 1) or ((bps ushr 4) and 0x01)).toByte()
        bytes[21] = ((bps and 0x0F) shl 4).toByte()
        return bytes
    }

    private fun buildWavHeader(
        audioFormat: Int,
        channels: Int,
        sampleRate: Int,
        bitDepth: Int,
        leadingJunkChunkSize: Int = 0,
    ): ByteArray {
        val total = 12 + (if (leadingJunkChunkSize > 0) 8 + leadingJunkChunkSize else 0) + 24
        val bytes = ByteArray(total)
        "RIFF".encodeToByteArray().copyInto(bytes, 0)
        writeU32LE(bytes, 4, (total - 8).toLong())
        "WAVE".encodeToByteArray().copyInto(bytes, 8)

        var pos = 12
        if (leadingJunkChunkSize > 0) {
            "JUNK".encodeToByteArray().copyInto(bytes, pos)
            writeU32LE(bytes, pos + 4, leadingJunkChunkSize.toLong())
            pos += 8 + leadingJunkChunkSize
        }

        "fmt ".encodeToByteArray().copyInto(bytes, pos)
        writeU32LE(bytes, pos + 4, 16L)
        writeU16LE(bytes, pos + 8, audioFormat)
        writeU16LE(bytes, pos + 10, channels)
        writeU32LE(bytes, pos + 12, sampleRate.toLong())
        writeU16LE(bytes, pos + 22, bitDepth)
        return bytes
    }

    private fun buildDsfHeader(channels: Int, sampleRate: Int, bitDepth: Int): ByteArray {
        val bytes = ByteArray(80)
        "DSD ".encodeToByteArray().copyInto(bytes, 0)
        "fmt ".encodeToByteArray().copyInto(bytes, 28)
        writeU32LE(bytes, 52, channels.toLong())
        writeU32LE(bytes, 56, sampleRate.toLong())
        writeU32LE(bytes, 60, bitDepth.toLong())
        return bytes
    }

    @Test
    fun parsesFlacCdAndHiResHeaders() {
        val cd = AudioHeaderInspector.inspectHeader(buildFlacHeader(44_100, 2, 16))
        assertNotNull(cd)
        assertEquals("FLAC", cd!!.codec)
        assertEquals(44_100, cd.sampleRateHz)
        assertEquals(2, cd.channelCount)
        assertEquals(16, cd.bitDepth)
        assertTrue(cd.isLossless)

        val hiRes = AudioHeaderInspector.inspectHeader(buildFlacHeader(192_000, 6, 24))
        assertNotNull(hiRes)
        assertEquals(192_000, hiRes!!.sampleRateHz)
        assertEquals(6, hiRes.channelCount)
        assertEquals(24, hiRes.bitDepth)
    }

    @Test
    fun parsesWavPcmFloatAndSkipsLeadingJunkChunk() {
        val pcm = AudioHeaderInspector.inspectHeader(buildWavHeader(1, 2, 96_000, 24))
        assertNotNull(pcm)
        assertEquals("WAV", pcm!!.codec)
        assertEquals(96_000, pcm.sampleRateHz)
        assertEquals(24, pcm.bitDepth)

        val floatWav = AudioHeaderInspector.inspectStream(
            ByteArrayInputStream(buildWavHeader(3, 2, 192_000, 32, leadingJunkChunkSize = 12)),
        )
        assertNotNull(floatWav)
        assertEquals("WAV Float", floatWav!!.codec)
        assertEquals(192_000, floatWav.sampleRateHz)
        assertEquals(32, floatWav.bitDepth)
    }

    @Test
    fun parsesDsfAndHandlesCorruptInputs() {
        val dsf = AudioHeaderInspector.inspectHeader(buildDsfHeader(2, 2_822_400, 1))
        assertNotNull(dsf)
        assertEquals("DSD", dsf!!.codec)
        assertEquals(2_822_400, dsf.sampleRateHz)
        assertEquals(1, dsf.bitDepth)

        assertNull(AudioHeaderInspector.inspectHeader(ByteArray(4)))
        assertNull(AudioHeaderInspector.inspectHeader("not-audio".encodeToByteArray()))
    }

    @Test
    fun fallbackCodecDistinguishesLosslessAndLossy() {
        assertEquals("FLAC" to true, AudioHeaderInspector.inferFallbackCodec("audio/flac", "track.flac"))
        assertEquals("ALAC" to true, AudioHeaderInspector.inferFallbackCodec(null, "song.alac"))
        assertEquals("MP3" to false, AudioHeaderInspector.inferFallbackCodec("audio/mpeg", "song.mp3"))
        assertFalse(AudioHeaderInspector.inferFallbackCodec("audio/opus", "song.opus").second)
    }

    private fun writeU16LE(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun writeU32LE(bytes: ByteArray, offset: Int, value: Long) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}
