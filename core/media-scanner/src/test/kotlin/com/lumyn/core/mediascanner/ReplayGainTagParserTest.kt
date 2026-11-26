package com.lumyn.core.mediascanner

import com.lumyn.core.mediascanner.metadata.ReplayGainTagParser
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReplayGainTagParserTest {

    @Test
    fun parsesFlacVorbisCommentsAndR128Tags() {
        val flac = buildMinimalFlacWithComments(
            listOf(
                "TITLE=Track One",
                "REPLAYGAIN_TRACK_GAIN=-6.50 dB",
                "REPLAYGAIN_TRACK_PEAK=0.981234",
                "REPLAYGAIN_ALBUM_GAIN=-7.10 dB",
                "REPLAYGAIN_ALBUM_PEAK=0.995000",
            ),
        )
        val tags = ReplayGainTagParser.parseBytes(flac)
        assertEquals(-6.50f, tags.trackGainDb!!, 0.01f)
        assertEquals(0.981234f, tags.trackPeak!!, 0.0001f)
        assertEquals(-7.10f, tags.albumGainDb!!, 0.01f)
        assertEquals(0.995f, tags.albumPeak!!, 0.0001f)
    }

    @Test
    fun parsesMp3Id3v2TxxxReplayGainFrames() {
        val id3 = buildMinimalId3v23WithTxxx(
            listOf(
                "REPLAYGAIN_TRACK_GAIN" to "-4.25 dB",
                "REPLAYGAIN_TRACK_PEAK" to "0.875",
            ),
        )
        val tags = ReplayGainTagParser.parseBytes(id3)
        assertEquals(-4.25f, tags.trackGainDb!!, 0.01f)
        assertEquals(0.875f, tags.trackPeak!!, 0.001f)
        assertNull(tags.albumGainDb)
    }

    @Test
    fun parsesExtendedHeaderAndUnsynchronizedId3v23Txxx() {
        // 1) ID3v2.3 with 6-byte extended header (flags = 0x40)
        val txxxTrack = ByteArrayOutputStream().apply {
            val payload = byteArrayOf(0) + "REPLAYGAIN_TRACK_GAIN\u0000-2.50 dB".toByteArray(Charsets.ISO_8859_1)
            write("TXXX".toByteArray(Charsets.US_ASCII))
            writeBeInt(this, payload.size)
            write(0)
            write(0)
            write(payload)
        }.toByteArray()
        val extBody = ByteArrayOutputStream().apply {
            writeBeInt(this, 6)
            write(ByteArray(6))
            write(txxxTrack)
        }.toByteArray()
        val extId3 = ByteArrayOutputStream().apply {
            write("ID3".toByteArray(Charsets.US_ASCII))
            write(3)
            write(0)
            write(0x40)
            writeSynchsafe(this, extBody.size)
            write(extBody)
        }.toByteArray()
        assertEquals(-2.50f, ReplayGainTagParser.parseBytes(extId3).trackGainDb!!, 0.01f)

        // 2) ID3v2.3 with tag-level unsynchronization (flags = 0x80) escaping UTF-16 BOM 0xFF 0xFE -> 0xFF 0x00 0xFE
        val utf16Payload = ByteArrayOutputStream().apply {
            write(1) // UTF-16 with BOM
            write("REPLAYGAIN_ALBUM_GAIN".toByteArray(Charsets.UTF_16))
            write(0)
            write(0)
            write("-8.25 dB".toByteArray(Charsets.UTF_16))
        }.toByteArray()
        val rawFrame = ByteArrayOutputStream().apply {
            write("TXXX".toByteArray(Charsets.US_ASCII))
            writeBeInt(this, utf16Payload.size)
            write(0)
            write(0)
            write(utf16Payload)
        }.toByteArray()
        val unsyncBody = ByteArrayOutputStream().apply {
            rawFrame.forEachIndexed { idx, b ->
                val v = b.toInt() and 0xFF
                write(v)
                if (v == 0xFF && idx + 1 < rawFrame.size) {
                    val next = rawFrame[idx + 1].toInt() and 0xFF
                    if (next == 0x00 || (next and 0xE0) == 0xE0 || next == 0xFE) {
                        write(0x00)
                    }
                }
            }
        }.toByteArray()
        val unsyncId3 = ByteArrayOutputStream().apply {
            write("ID3".toByteArray(Charsets.US_ASCII))
            write(3)
            write(0)
            write(0x80)
            writeSynchsafe(this, unsyncBody.size)
            write(unsyncBody)
        }.toByteArray()
        assertEquals(-8.25f, ReplayGainTagParser.parseBytes(unsyncId3).albumGainDb!!, 0.01f)
    }

    @Test
    fun parsesOggOpusTagsWithR128Gain() {
        val ogg = buildMinimalOpusOgg(listOf("R128_TRACK_GAIN=-512"))
        val tags = ReplayGainTagParser.parseBytes(ogg)
        // -512 / 256 = -2.0 LUFS relative + 5.0 dB offset = +3.0 dB
        assertEquals(3.0f, tags.trackGainDb!!, 0.01f)
    }

    @Test
    fun parsesMp4FreeformAtoms() {
        val mp4 = buildMinimalMp4WithFreeform(
            listOf(
                "replaygain_track_gain" to "-5.40 dB",
                "replaygain_track_peak" to "0.912",
            ),
        )
        val tags = ReplayGainTagParser.parseBytes(mp4)
        assertEquals(-5.40f, tags.trackGainDb!!, 0.01f)
        assertEquals(0.912f, tags.trackPeak!!, 0.001f)
    }

    @Test
    fun parsesSeekableFlacAndId3v2WhenLargeArtworkPrecedesReplayGainTags() {
        val flacWithArt = buildFlacWithLargePictureBeforeComments(
            pictureBytes = 100_000,
            comments = listOf(
                "REPLAYGAIN_TRACK_GAIN=-5.25 dB",
                "REPLAYGAIN_TRACK_PEAK=0.910000",
            ),
        )
        val flacTags = ReplayGainTagParser.parseSeekableFlac(InMemorySeekableByteChannel(flacWithArt))
        assertEquals(-5.25f, flacTags.trackGainDb!!, 0.01f)
        assertEquals(0.91f, flacTags.trackPeak!!, 0.001f)

        val id3WithApic = buildId3v23WithLargeApicBeforeTxxx(
            apicBytes = 100_000,
            entries = listOf(
                "REPLAYGAIN_ALBUM_GAIN" to "-7.75 dB",
                "REPLAYGAIN_ALBUM_PEAK" to "0.980000",
            ),
        )
        val id3Tags = ReplayGainTagParser.parseSeekableId3v2(InMemorySeekableByteChannel(id3WithApic))
        assertEquals(-7.75f, id3Tags.albumGainDb!!, 0.01f)
        assertEquals(0.98f, id3Tags.albumPeak!!, 0.001f)
    }

    private fun buildFlacWithLargePictureBeforeComments(
        pictureBytes: Int,
        comments: List<String>,
    ): ByteArray {
        val vorbis = encodeVorbisComments("TestVendor", comments)
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray(Charsets.US_ASCII))
        // STREAMINFO (type 0, non-last, 34 bytes)
        out.write(0)
        out.write(0)
        out.write(0)
        out.write(34)
        out.write(ByteArray(34))
        // PICTURE (type 6, non-last)
        out.write(6)
        out.write((pictureBytes ushr 16) and 0xFF)
        out.write((pictureBytes ushr 8) and 0xFF)
        out.write(pictureBytes and 0xFF)
        out.write(ByteArray(pictureBytes))
        // VORBIS_COMMENT (type 4, last = 0x84)
        out.write(0x84)
        out.write((vorbis.size ushr 16) and 0xFF)
        out.write((vorbis.size ushr 8) and 0xFF)
        out.write(vorbis.size and 0xFF)
        out.write(vorbis)
        return out.toByteArray()
    }

    private fun buildId3v23WithLargeApicBeforeTxxx(
        apicBytes: Int,
        entries: List<Pair<String, String>>,
    ): ByteArray {
        val frames = ByteArrayOutputStream()
        frames.write("APIC".toByteArray(Charsets.US_ASCII))
        writeBeInt(frames, apicBytes)
        frames.write(0)
        frames.write(0)
        frames.write(ByteArray(apicBytes))
        entries.forEach { (desc, value) ->
            val payload = ByteArrayOutputStream()
            payload.write(0)
            payload.write(desc.toByteArray(Charsets.ISO_8859_1))
            payload.write(0)
            payload.write(value.toByteArray(Charsets.ISO_8859_1))
            val pBytes = payload.toByteArray()
            frames.write("TXXX".toByteArray(Charsets.US_ASCII))
            writeBeInt(frames, pBytes.size)
            frames.write(0)
            frames.write(0)
            frames.write(pBytes)
        }
        val body = frames.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("ID3".toByteArray(Charsets.US_ASCII))
        out.write(3)
        out.write(0)
        out.write(0)
        writeSynchsafe(out, body.size)
        out.write(body)
        return out.toByteArray()
    }

    private class InMemorySeekableByteChannel(private val data: ByteArray) : SeekableByteChannel {
        private var pos = 0L
        private var open = true

        override fun read(dst: ByteBuffer): Int {
            if (pos >= data.size) return -1
            val count = minOf(dst.remaining(), (data.size - pos).toInt())
            dst.put(data, pos.toInt(), count)
            pos += count
            return count
        }

        override fun write(src: ByteBuffer): Int = throw UnsupportedOperationException()
        override fun position(): Long = pos
        override fun position(newPosition: Long): SeekableByteChannel {
            pos = newPosition.coerceAtLeast(0L)
            return this
        }

        override fun size(): Long = data.size.toLong()
        override fun truncate(size: Long): SeekableByteChannel = throw UnsupportedOperationException()
        override fun isOpen(): Boolean = open
        override fun close() {
            open = false
        }
    }

    private fun buildMinimalFlacWithComments(comments: List<String>): ByteArray {
        val vorbis = encodeVorbisComments("TestVendor", comments)
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray(Charsets.US_ASCII))
        // STREAMINFO (type 0, non-last, 34 bytes)
        out.write(0)
        out.write(0)
        out.write(0)
        out.write(34)
        out.write(ByteArray(34))
        // VORBIS_COMMENT (type 4, last = 0x84)
        out.write(0x84)
        out.write((vorbis.size ushr 16) and 0xFF)
        out.write((vorbis.size ushr 8) and 0xFF)
        out.write(vorbis.size and 0xFF)
        out.write(vorbis)
        return out.toByteArray()
    }

    private fun encodeVorbisComments(vendor: String, comments: List<String>): ByteArray {
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

    private fun buildMinimalId3v23WithTxxx(entries: List<Pair<String, String>>): ByteArray {
        val frames = ByteArrayOutputStream()
        entries.forEach { (desc, value) ->
            val payload = ByteArrayOutputStream()
            payload.write(0) // ISO-8859-1
            payload.write(desc.toByteArray(Charsets.ISO_8859_1))
            payload.write(0)
            payload.write(value.toByteArray(Charsets.ISO_8859_1))
            val pBytes = payload.toByteArray()
            frames.write("TXXX".toByteArray(Charsets.US_ASCII))
            writeBeInt(frames, pBytes.size)
            frames.write(0)
            frames.write(0)
            frames.write(pBytes)
        }
        val body = frames.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("ID3".toByteArray(Charsets.US_ASCII))
        out.write(3)
        out.write(0)
        out.write(0)
        writeSynchsafe(out, body.size)
        out.write(body)
        return out.toByteArray()
    }

    private fun buildMinimalOpusOgg(comments: List<String>): ByteArray {
        val packet = ByteArrayOutputStream()
        packet.write("OpusTags".toByteArray(Charsets.US_ASCII))
        packet.write(encodeVorbisComments("libopus", comments))
        val pBytes = packet.toByteArray()
        require(pBytes.size < 255)
        val out = ByteArrayOutputStream()
        out.write("OggS".toByteArray(Charsets.US_ASCII))
        out.write(0) // version
        out.write(0) // header type
        out.write(ByteArray(8)) // granule
        out.write(ByteArray(4)) // serial
        out.write(ByteArray(4)) // seq
        out.write(ByteArray(4)) // crc
        out.write(1) // 1 segment
        out.write(pBytes.size)
        out.write(pBytes)
        return out.toByteArray()
    }

    private fun buildMinimalMp4WithFreeform(entries: List<Pair<String, String>>): ByteArray {
        val freeforms = entries.map { (name, value) ->
            val nameBox = fullMp4Box("name", name.toByteArray(Charsets.UTF_8))
            val dataBody = ByteBuffer.allocate(8 + value.toByteArray(Charsets.UTF_8).size).apply {
                putInt(1)
                putInt(0)
                put(value.toByteArray(Charsets.UTF_8))
            }.array()
            val dataBox = mp4Box("data", dataBody)
            mp4Box("----", nameBox + dataBox)
        }.fold(ByteArray(0)) { acc, next -> acc + next }

        val ilst = mp4Box("ilst", freeforms)
        val meta = fullMp4Box("meta", ilst)
        val udta = mp4Box("udta", meta)
        val moov = mp4Box("moov", udta)
        val ftyp = mp4Box("ftyp", "M4A ".toByteArray(Charsets.US_ASCII) + ByteArray(8))
        return ftyp + moov
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

    private fun writeBeInt(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 24) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    private fun writeSynchsafe(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 21) and 0x7F)
        out.write((v ushr 14) and 0x7F)
        out.write((v ushr 7) and 0x7F)
        out.write(v and 0x7F)
    }
}
