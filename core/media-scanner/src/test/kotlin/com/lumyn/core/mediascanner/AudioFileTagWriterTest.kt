package com.lumyn.core.mediascanner

import com.lumyn.core.mediascanner.metadata.AudioHeaderInspector
import com.lumyn.core.mediascanner.metadata.ReplayGainTagParser
import com.lumyn.core.mediascanner.tags.AudioFileTagWriter
import com.lumyn.core.mediascanner.tags.FileTagPatch
import com.lumyn.core.mediascanner.tags.FileTagWriteOutcome
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AudioFileTagWriterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun flacRoundTripPreservesReplayGainAudioFramesAndUpdatesMetadata() {
        val inFile = tempFolder.newFile("sample.flac")
        val outFile = tempFolder.newFile("sample_out.flac")
        val audioPayload = byteArrayOf(0xFF.toByte(), 0xF8.toByte(), 0x11, 0x22, 0x33, 0x44, 0x55)

        val streamInfo = ByteArray(34)
        // Encode 96kHz, 2ch, 24-bit in STREAMINFO bytes 10..13 (header offset 18..21 minus 8)
        streamInfo[10] = ((96_000 ushr 12) and 0xFF).toByte()
        streamInfo[11] = ((96_000 ushr 4) and 0xFF).toByte()
        streamInfo[12] = (((96_000 and 0x0F) shl 4) or (1 shl 1) or 1).toByte()
        streamInfo[13] = (7 shl 4).toByte()

        val initialVorbis = encodeVorbis(
            "Encoder",
            listOf(
                "TITLE=Old Title",
                "REPLAYGAIN_TRACK_GAIN=-5.25 dB",
                "LYRICS=[00:05.00]Keep me",
            ),
        )
        val inputBytes = ByteArrayOutputStream().apply {
            write("fLaC".toByteArray(Charsets.US_ASCII))
            write(0)
            write(0)
            write(0)
            write(34)
            write(streamInfo)
            write(0x84)
            write((initialVorbis.size ushr 16) and 0xFF)
            write((initialVorbis.size ushr 8) and 0xFF)
            write(initialVorbis.size and 0xFF)
            write(initialVorbis)
            write(audioPayload)
        }.toByteArray()
        inFile.writeBytes(inputBytes)

        val outcome = AudioFileTagWriter.write(
            inFile,
            outFile,
            FileTagPatch(
                title = "New Hi-Res Track",
                artist = "Studio Artist",
                album = "Master Session",
                trackNumber = 3,
                year = 2025,
                genre = "Classical",
                coverArtJpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()),
            ),
        )
        assertEquals(FileTagWriteOutcome.WRITTEN, outcome)

        val outBytes = outFile.readBytes()
        val parsedHeader = AudioHeaderInspector.inspectHeader(outBytes)!!
        assertEquals(96_000, parsedHeader.sampleRateHz)
        assertEquals(24, parsedHeader.bitDepth)

        val rg = ReplayGainTagParser.parseBytes(outBytes)
        assertEquals(-5.25f, rg.trackGainDb!!, 0.01f)

        val outText = String(outBytes, Charsets.UTF_8)
        assertTrue(outText.contains("TITLE=New Hi-Res Track"))
        assertTrue(outText.contains("ARTIST=Studio Artist"))
        assertTrue(outText.contains("LYRICS=[00:05.00]Keep me"))
        assertArrayEquals(audioPayload, outBytes.copyOfRange(outBytes.size - audioPayload.size, outBytes.size))
    }

    @Test
    fun mp3RoundTripPreservesUsltAndTxxxFrames() {
        val inFile = tempFolder.newFile("sample.mp3")
        val outFile = tempFolder.newFile("sample_out.mp3")
        val mpegAudio = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64, 0x01, 0x02, 0x03)

        val existingFrames = ByteArrayOutputStream().apply {
            val tit2 = byteArrayOf(3) + "Old".toByteArray(Charsets.UTF_8)
            writeFrame(this, "TIT2", tit2)
            val txxx = byteArrayOf(0) + "REPLAYGAIN_TRACK_GAIN\u0000-4.00 dB".toByteArray(Charsets.ISO_8859_1)
            writeFrame(this, "TXXX", txxx)
            val uslt = byteArrayOf(0, 'e'.code.toByte(), 'n'.code.toByte(), 'g'.code.toByte(), 0) +
                "Verse line".toByteArray(Charsets.ISO_8859_1)
            writeFrame(this, "USLT", uslt)
        }.toByteArray()

        val initialMp3 = ByteArrayOutputStream().apply {
            write("ID3".toByteArray(Charsets.US_ASCII))
            write(3)
            write(0)
            write(0)
            writeSynchsafe(this, existingFrames.size)
            write(existingFrames)
            write(mpegAudio)
        }.toByteArray()
        inFile.writeBytes(initialMp3)

        val outcome = AudioFileTagWriter.write(
            inFile,
            outFile,
            FileTagPatch("Fresh Title", "Fresh Artist", "Fresh Album", 2, 2024, "Electronic"),
        )
        assertEquals(FileTagWriteOutcome.WRITTEN, outcome)

        val outBytes = outFile.readBytes()
        val rg = ReplayGainTagParser.parseBytes(outBytes)
        assertEquals(-4.00f, rg.trackGainDb!!, 0.01f)
        assertArrayEquals(mpegAudio, outBytes.copyOfRange(outBytes.size - mpegAudio.size, outBytes.size))
    }

    @Test
    fun mp4BeforeMdatShiftsStcoOffsetsAndPreservesMdatPayload() {
        val inFile = tempFolder.newFile("sample.m4a")
        val outFile = tempFolder.newFile("sample_out.m4a")

        val ftyp = mp4Box("ftyp", "M4A ".toByteArray(Charsets.US_ASCII) + ByteArray(8))
        val mdatPayload = byteArrayOf(0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28)
        val mdat = mp4Box("mdat", mdatPayload)

        val freeformName = fullMp4Box("name", "replaygain_track_gain".toByteArray(Charsets.UTF_8))
        val freeformData = mp4Box("data", ByteBuffer.allocate(15).putInt(1).putInt(0).put("-3.1 dB".toByteArray()).array())
        val freeform = mp4Box("----", freeformName + freeformData)
        val initialIlst = mp4Box("ilst", freeform)
        val initialMeta = fullMp4Box("meta", initialIlst)
        val initialUdta = mp4Box("udta", initialMeta)

        // Placeholder stco before computing offset
        val placeholderStco = fullMp4Box("stco", ByteBuffer.allocate(8).putInt(1).putInt(0).array())
        val initialStbl = mp4Box("stbl", placeholderStco)
        val initialMinf = mp4Box("minf", initialStbl)
        val initialMdia = mp4Box("mdia", initialMinf)
        val initialTrak = mp4Box("trak", initialMdia)
        val initialMoov = mp4Box("moov", initialTrak + initialUdta)

        // Patch real initial chunk offset = ftyp.size + initialMoov.size + 8 (mdat payload start)
        val initialChunkOffset = ftyp.size + initialMoov.size + 8
        val realStco = fullMp4Box("stco", ByteBuffer.allocate(8).putInt(1).putInt(initialChunkOffset).array())
        val realMoov = mp4Box("moov", mp4Box("trak", mp4Box("mdia", mp4Box("minf", mp4Box("stbl", realStco)))) + initialUdta)
        inFile.writeBytes(ftyp + realMoov + mdat)

        val outcome = AudioFileTagWriter.write(
            inFile,
            outFile,
            FileTagPatch("Lossless ALAC", "Master", "Reference Album", 5, 2025, "Jazz"),
        )
        assertEquals(FileTagWriteOutcome.WRITTEN, outcome)

        val outBytes = outFile.readBytes()
        val rg = ReplayGainTagParser.parseBytes(outBytes)
        assertEquals(-3.1f, rg.trackGainDb!!, 0.01f)

        val updatedStcoOffset = findFirstStcoOffset(outBytes)
        assertTrue(updatedStcoOffset > initialChunkOffset)
        assertArrayEquals(
            mdatPayload,
            outBytes.copyOfRange(updatedStcoOffset, updatedStcoOffset + mdatPayload.size),
        )
    }

    @Test
    fun taglessMp4SynthesizesHdlrAtomAndShiftsStcoOffsets() {
        val inFile = tempFolder.newFile("tagless.m4a")
        val outFile = tempFolder.newFile("tagless_out.m4a")

        val ftyp = mp4Box("ftyp", "M4A ".toByteArray(Charsets.US_ASCII) + ByteArray(8))
        val mdatPayload = byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70, 0x71)
        val mdat = mp4Box("mdat", mdatPayload)

        val placeholderStco = fullMp4Box("stco", ByteBuffer.allocate(8).putInt(1).putInt(0).array())
        val initialMoov = mp4Box("moov", mp4Box("trak", mp4Box("mdia", mp4Box("minf", mp4Box("stbl", placeholderStco)))))
        val initialChunkOffset = ftyp.size + initialMoov.size + 8
        val realStco = fullMp4Box("stco", ByteBuffer.allocate(8).putInt(1).putInt(initialChunkOffset).array())
        val realMoov = mp4Box("moov", mp4Box("trak", mp4Box("mdia", mp4Box("minf", mp4Box("stbl", realStco)))))
        inFile.writeBytes(ftyp + realMoov + mdat)

        val outcome = AudioFileTagWriter.write(
            inFile,
            outFile,
            FileTagPatch("Tagless Master", "Studio", "First Pressing", 1, 2025, "Ambient"),
        )
        assertEquals(FileTagWriteOutcome.WRITTEN, outcome)

        val outBytes = outFile.readBytes()
        val updatedStcoOffset = findFirstStcoOffset(outBytes)
        assertTrue(updatedStcoOffset > initialChunkOffset)
        assertArrayEquals(
            mdatPayload,
            outBytes.copyOfRange(updatedStcoOffset, updatedStcoOffset + mdatPayload.size),
        )

        val metaOffset = findFirstBoxOffset(outBytes, "meta")
        val metaChildOffset = metaOffset + 12
        val firstChildType = String(outBytes, metaChildOffset + 4, 4, Charsets.ISO_8859_1)
        assertEquals("hdlr", firstChildType)
        val hdlrHandler = String(outBytes, metaChildOffset + 16, 4, Charsets.ISO_8859_1)
        assertEquals("mdir", hdlrHandler)
    }

    @Test
    fun mp3PreservesFrameFlagsAndSkipsId3v23ExtendedHeader() {
        val inFile = tempFolder.newFile("ext_flags.mp3")
        val outFile = tempFolder.newFile("ext_flags_out.mp3")
        val mpegAudio = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64, 0x09, 0x08, 0x07)

        val body = ByteArrayOutputStream().apply {
            // ID3v2.3 extended header: 4-byte size (6) + 6-byte body
            write(byteArrayOf(0, 0, 0, 6))
            write(ByteArray(6))
            val tit2 = byteArrayOf(0) + "Old Title".toByteArray(Charsets.ISO_8859_1)
            writeFrame(this, "TIT2", tit2)
            val txxx = byteArrayOf(0) + "REPLAYGAIN_TRACK_GAIN\u0000-3.75 dB".toByteArray(Charsets.ISO_8859_1)
            writeFrameWithFlags(this, "TXXX", txxx, flag0 = 0x40, flag1 = 0x20)
        }.toByteArray()

        val initialMp3 = ByteArrayOutputStream().apply {
            write("ID3".toByteArray(Charsets.US_ASCII))
            write(3)
            write(0)
            write(0x40) // extended header present
            writeSynchsafe(this, body.size)
            write(body)
            write(mpegAudio)
        }.toByteArray()
        inFile.writeBytes(initialMp3)

        val outcome = AudioFileTagWriter.write(
            inFile,
            outFile,
            FileTagPatch("Updated Track", "Artist", "Album", 4, 2025, "Jazz"),
        )
        assertEquals(FileTagWriteOutcome.WRITTEN, outcome)

        val outBytes = outFile.readBytes()
        val rg = ReplayGainTagParser.parseBytes(outBytes)
        assertEquals(-3.75f, rg.trackGainDb!!, 0.01f)
        assertArrayEquals(mpegAudio, outBytes.copyOfRange(outBytes.size - mpegAudio.size, outBytes.size))

        val txxxFlags = findId3FrameFlags(outBytes, "TXXX")
        assertEquals(0x40 to 0x20, txxxFlags)
    }

    private fun findFirstBoxOffset(bytes: ByteArray, fourCc: String): Int {
        for (i in 4 until bytes.size - 8) {
            if (bytes[i] == fourCc[0].code.toByte() &&
                bytes[i + 1] == fourCc[1].code.toByte() &&
                bytes[i + 2] == fourCc[2].code.toByte() &&
                bytes[i + 3] == fourCc[3].code.toByte()
            ) {
                return i - 4
            }
        }
        throw AssertionError("$fourCc box not found")
    }

    private fun findId3FrameFlags(bytes: ByteArray, targetId: String): Pair<Int, Int> {
        val major = bytes[3].toInt() and 0xFF
        val tagSize = ((bytes[6].toInt() and 0x7F) shl 21) or
            ((bytes[7].toInt() and 0x7F) shl 14) or
            ((bytes[8].toInt() and 0x7F) shl 7) or
            (bytes[9].toInt() and 0x7F)
        var pos = 10
        val end = minOf(bytes.size, 10 + tagSize)
        while (pos + 10 <= end) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = if (major >= 4) {
                ((bytes[pos + 4].toInt() and 0x7F) shl 21) or
                    ((bytes[pos + 5].toInt() and 0x7F) shl 14) or
                    ((bytes[pos + 6].toInt() and 0x7F) shl 7) or
                    (bytes[pos + 7].toInt() and 0x7F)
            } else {
                ByteBuffer.wrap(bytes, pos + 4, 4).int
            }
            if (id == targetId) {
                return (bytes[pos + 8].toInt() and 0xFF) to (bytes[pos + 9].toInt() and 0xFF)
            }
            pos += 10 + size
        }
        throw AssertionError("Frame $targetId not found")
    }

    private fun writeFrameWithFlags(
        out: ByteArrayOutputStream,
        id: String,
        payload: ByteArray,
        flag0: Int,
        flag1: Int,
    ) {
        out.write(id.toByteArray(Charsets.US_ASCII))
        out.write((payload.size ushr 24) and 0xFF)
        out.write((payload.size ushr 16) and 0xFF)
        out.write((payload.size ushr 8) and 0xFF)
        out.write(payload.size and 0xFF)
        out.write(flag0 and 0xFF)
        out.write(flag1 and 0xFF)
        out.write(payload)
    }

    private fun findFirstStcoOffset(bytes: ByteArray): Int {
        for (i in 4 until bytes.size - 16) {
            if (bytes[i] == 's'.code.toByte() &&
                bytes[i + 1] == 't'.code.toByte() &&
                bytes[i + 2] == 'c'.code.toByte() &&
                bytes[i + 3] == 'o'.code.toByte()
            ) {
                return ByteBuffer.wrap(bytes, i + 12, 4).int
            }
        }
        throw AssertionError("stco box not found")
    }

    private fun writeFrame(out: ByteArrayOutputStream, id: String, payload: ByteArray) {
        out.write(id.toByteArray(Charsets.US_ASCII))
        out.write((payload.size ushr 24) and 0xFF)
        out.write((payload.size ushr 16) and 0xFF)
        out.write((payload.size ushr 8) and 0xFF)
        out.write(payload.size and 0xFF)
        out.write(0)
        out.write(0)
        out.write(payload)
    }

    private fun encodeVorbis(vendor: String, comments: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        val vb = vendor.toByteArray(Charsets.UTF_8)
        writeLeInt(out, vb.size)
        out.write(vb)
        writeLeInt(out, comments.size)
        comments.forEach { c ->
            val b = c.toByteArray(Charsets.UTF_8)
            writeLeInt(out, b.size)
            out.write(b)
        }
        return out.toByteArray()
    }

    private fun mp4Box(type: String, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + payload.size).apply {
            putInt(8 + payload.size)
            put(type.toByteArray(Charsets.ISO_8859_1))
            put(payload)
        }.array()

    private fun fullMp4Box(type: String, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(12 + payload.size).apply {
            putInt(12 + payload.size)
            put(type.toByteArray(Charsets.ISO_8859_1))
            putInt(0)
            put(payload)
        }.array()

    private fun writeLeInt(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 24) and 0xFF)
    }

    private fun writeSynchsafe(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 21) and 0x7F)
        out.write((v ushr 14) and 0x7F)
        out.write((v ushr 7) and 0x7F)
        out.write(v and 0x7F)
    }
}
