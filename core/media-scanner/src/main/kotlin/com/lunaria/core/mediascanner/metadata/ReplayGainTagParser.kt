package com.lunaria.core.mediascanner.metadata

import com.lunaria.core.model.ReplayGainCalculator
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.charset.Charset

data class ReplayGainTags(
    val trackGainDb: Float? = null,
    val albumGainDb: Float? = null,
    val trackPeak: Float? = null,
    val albumPeak: Float? = null,
)

/**
 * Pure-Kotlin ReplayGain & R128 metadata parser for FLAC (`VORBIS_COMMENT`),
 * MP3 (`ID3v2 TXXX`), Ogg Vorbis / Opus (`OggS`), and MP4 / M4A (`----` freeform atoms).
 */
object ReplayGainTagParser {

    fun parseStream(input: InputStream, hintBytes: ByteArray? = null): ReplayGainTags {
        val header = hintBytes ?: readBounded(input, MAX_TAG_BYTES)
        return parseBytes(header)
    }

    fun parseSeekableFlac(channel: SeekableByteChannel): ReplayGainTags {
        val fileSize = channel.size()
        if (fileSize < 8L) return ReplayGainTags()
        val magicBuf = ByteBuffer.allocate(4)
        channel.position(0L)
        if (readFully(channel, magicBuf) < 4) return ReplayGainTags()
        if (!matchesAscii(magicBuf.array(), 4, 0, "fLaC")) return ReplayGainTags()

        var offset = 4L
        val hdrBuf = ByteBuffer.allocate(4)
        while (offset + 4L <= fileSize) {
            hdrBuf.clear()
            channel.position(offset)
            if (readFully(channel, hdrBuf) < 4) break
            val hdr = hdrBuf.array()
            val headerByte = hdr[0].toInt() and 0xFF
            val isLast = (headerByte and 0x80) != 0
            val blockType = headerByte and 0x7F
            val blockSize = ((hdr[1].toInt() and 0xFF) shl 16) or
                ((hdr[2].toInt() and 0xFF) shl 8) or
                (hdr[3].toInt() and 0xFF)
            val dataStart = offset + 4L
            if (blockSize < 0 || dataStart + blockSize > fileSize) break
            if (blockType == 4) {
                val readLen = blockSize.coerceAtMost(MAX_FLAC_COMMENT_BYTES)
                if (readLen <= 0) return ReplayGainTags()
                val buf = ByteBuffer.allocate(readLen)
                channel.position(dataStart)
                val read = readFully(channel, buf)
                return if (read > 0) parseVorbisComments(buf.array(), 0, read) else ReplayGainTags()
            }
            offset = dataStart + blockSize
            if (isLast) break
        }
        return ReplayGainTags()
    }

    fun parseSeekableId3v2(channel: SeekableByteChannel): ReplayGainTags {
        val fileSize = channel.size()
        if (fileSize < 10L) return ReplayGainTags()
        val hdrBuf = ByteBuffer.allocate(10)
        channel.position(0L)
        if (readFully(channel, hdrBuf) < 10) return ReplayGainTags()
        val hdr = hdrBuf.array()
        if (!matchesAscii(hdr, 10, 0, "ID3")) return ReplayGainTags()
        val majorVersion = hdr[3].toInt() and 0xFF
        if (majorVersion < 3) return ReplayGainTags()
        val flags = hdr[5].toInt() and 0xFF
        val isUnsync = (flags and 0x80) != 0
        val tagSize = decodeSynchsafe(hdr, 6)
        val tagEnd = (10L + tagSize).coerceAtMost(fileSize)

        if (isUnsync) {
            val totalToRead = tagEnd.coerceAtMost(MAX_ID3_UNSYNC_BYTES.toLong()).toInt()
            if (totalToRead < 10) return ReplayGainTags()
            val buf = ByteBuffer.allocate(totalToRead)
            channel.position(0L)
            val read = readFully(channel, buf)
            return if (read >= 10) parseId3v2(buf.array(), read) else ReplayGainTags()
        }

        var pos = 10L
        if ((flags and 0x40) != 0 && pos + 4L <= tagEnd) {
            val extBuf = ByteBuffer.allocate(4)
            channel.position(pos)
            if (readFully(channel, extBuf) < 4) return ReplayGainTags()
            val extArr = extBuf.array()
            val extSize = if (majorVersion >= 4) decodeSynchsafe(extArr, 0) else readIntBe(extArr, 0)
            pos += if (majorVersion >= 4) extSize.coerceAtLeast(4).toLong() else 4L + extSize.coerceAtLeast(0).toLong()
        }

        val acc = TagAccumulator()
        val frameHdrBuf = ByteBuffer.allocate(10)
        while (pos + 10L <= tagEnd) {
            frameHdrBuf.clear()
            channel.position(pos)
            if (readFully(channel, frameHdrBuf) < 10) break
            val fHdr = frameHdrBuf.array()
            val frameId = String(fHdr, 0, 4, Charsets.US_ASCII)
            if (frameId.all { it == '\u0000' }) break
            val frameSize = if (majorVersion >= 4) decodeSynchsafe(fHdr, 4) else readIntBe(fHdr, 4)
            val frameFlags1 = fHdr[9].toInt() and 0xFF
            val dataStart = pos + 10L
            if (frameSize <= 0 || dataStart + frameSize > tagEnd) break
            if (frameId == "TXXX") {
                val readLen = frameSize.coerceAtMost(MAX_ID3_TXXX_BYTES)
                val payloadBuf = ByteBuffer.allocate(readLen)
                channel.position(dataStart)
                val read = readFully(channel, payloadBuf)
                if (read > 0) {
                    val payload = payloadBuf.array()
                    val decodedFrame = if (majorVersion >= 4 && (frameFlags1 and 0x02) != 0) {
                        val clean = removeId3Unsynchronization(payload, 0, read)
                        parseId3Txxx(clean, 0, clean.size)
                    } else {
                        parseId3Txxx(payload, 0, read)
                    }
                    decodedFrame?.let { (desc, value) -> acc.accept(desc, value) }
                }
            }
            pos = dataStart + frameSize.toLong()
        }
        return acc.build()
    }

    fun parseSeekableMp4(channel: SeekableByteChannel): ReplayGainTags {
        val fileSize = channel.size()
        var offset = 0L
        val hdrBuf = ByteBuffer.allocate(16)
        while (offset + 8L <= fileSize) {
            hdrBuf.clear()
            hdrBuf.limit(8)
            channel.position(offset)
            if (readFully(channel, hdrBuf) < 8) break
            hdrBuf.flip()
            val rawSize = hdrBuf.int.toLong() and 0xFFFF_FFFFL
            val type = readAscii4(hdrBuf)
            val headerSize: Long
            val boxSize: Long
            if (rawSize == 1L) {
                if (offset + 16L > fileSize) break
                hdrBuf.clear()
                hdrBuf.limit(8)
                if (readFully(channel, hdrBuf) < 8) break
                hdrBuf.flip()
                boxSize = hdrBuf.long
                headerSize = 16L
            } else if (rawSize == 0L) {
                boxSize = fileSize - offset
                headerSize = 8L
            } else {
                boxSize = rawSize
                headerSize = 8L
            }
            if (boxSize < headerSize || offset + boxSize > fileSize) break
            if (type == "moov") {
                val payloadLen = (boxSize - headerSize).coerceAtMost(MAX_MP4_MOOV_BYTES.toLong()).toInt()
                if (payloadLen <= 0) return ReplayGainTags()
                val moovBuf = ByteBuffer.allocate(payloadLen)
                channel.position(offset + headerSize)
                val read = readFully(channel, moovBuf)
                return if (read > 0) parseMp4Container(moovBuf.array(), 0, read) else ReplayGainTags()
            }
            offset += boxSize
        }
        return ReplayGainTags()
    }

    fun parseBytes(bytes: ByteArray, length: Int = bytes.size): ReplayGainTags {
        val len = minOf(length, bytes.size)
        if (len < 12) return ReplayGainTags()
        return when {
            matchesAscii(bytes, len, 0, "fLaC") -> parseFlac(bytes, len)
            matchesAscii(bytes, len, 0, "ID3") -> parseId3v2(bytes, len)
            matchesAscii(bytes, len, 0, "OggS") -> parseOgg(bytes, len)
            matchesAscii(bytes, len, 4, "ftyp") -> parseMp4TopLevel(bytes, len)
            else -> ReplayGainTags()
        }
    }

    private fun parseFlac(bytes: ByteArray, len: Int): ReplayGainTags {
        var offset = 4
        while (offset + 4 <= len) {
            val headerByte = bytes[offset].toInt() and 0xFF
            val isLast = (headerByte and 0x80) != 0
            val blockType = headerByte and 0x7F
            val blockSize = ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
            val dataStart = offset + 4
            if (blockSize < 0 || dataStart + blockSize > len) break
            if (blockType == 4) {
                return parseVorbisComments(bytes, dataStart, dataStart + blockSize)
            }
            offset = dataStart + blockSize
            if (isLast) break
        }
        return ReplayGainTags()
    }

    internal fun parseVorbisComments(bytes: ByteArray, start: Int, end: Int): ReplayGainTags {
        var pos = start
        if (pos + 4 > end) return ReplayGainTags()
        val vendorLen = readIntLe(bytes, pos)
        if (vendorLen < 0 || pos + 4L + vendorLen > end) return ReplayGainTags()
        pos += 4 + vendorLen
        if (pos + 4 > end) return ReplayGainTags()
        val count = readIntLe(bytes, pos).coerceIn(0, 1024)
        pos += 4

        val acc = TagAccumulator()
        repeat(count) {
            if (pos + 4 > end) return acc.build()
            val entryLen = readIntLe(bytes, pos)
            pos += 4
            if (entryLen < 0 || pos + entryLen > end) return acc.build()
            val entry = String(bytes, pos, entryLen, Charsets.UTF_8)
            pos += entryLen
            val eqIdx = entry.indexOf('=')
            if (eqIdx > 0) {
                acc.accept(entry.substring(0, eqIdx), entry.substring(eqIdx + 1))
            }
        }
        return acc.build()
    }

    private fun parseId3v2(bytes: ByteArray, len: Int): ReplayGainTags {
        if (len < 10) return ReplayGainTags()
        val majorVersion = bytes[3].toInt() and 0xFF
        if (majorVersion < 3) return ReplayGainTags()
        val flags = bytes[5].toInt() and 0xFF
        val isUnsync = (flags and 0x80) != 0
        val tagSize = decodeSynchsafe(bytes, 6)
        val rawEnd = (10 + tagSize).coerceAtMost(len)
        val tagBody: ByteArray
        var pos: Int
        val tagEnd: Int
        if (isUnsync) {
            tagBody = removeId3Unsynchronization(bytes, 10, rawEnd)
            pos = 0
            tagEnd = tagBody.size
        } else {
            tagBody = bytes
            pos = 10
            tagEnd = rawEnd
        }

        if ((flags and 0x40) != 0 && pos + 4 <= tagEnd) {
            val extSize = if (majorVersion >= 4) decodeSynchsafe(tagBody, pos) else readIntBe(tagBody, pos)
            pos += if (majorVersion >= 4) extSize.coerceAtLeast(4) else 4 + extSize.coerceAtLeast(0)
        }

        val acc = TagAccumulator()
        while (pos + 10 <= tagEnd) {
            val frameId = String(tagBody, pos, 4, Charsets.US_ASCII)
            if (frameId.all { it == '\u0000' }) break
            val frameSize = if (majorVersion >= 4) decodeSynchsafe(tagBody, pos + 4) else readIntBe(tagBody, pos + 4)
            val frameFlags1 = tagBody[pos + 9].toInt() and 0xFF
            val dataStart = pos + 10
            if (frameSize <= 0 || dataStart + frameSize > tagEnd) break
            if (frameId == "TXXX") {
                val decodedFrame = if (majorVersion >= 4 && !isUnsync && (frameFlags1 and 0x02) != 0) {
                    val clean = removeId3Unsynchronization(tagBody, dataStart, dataStart + frameSize)
                    parseId3Txxx(clean, 0, clean.size)
                } else {
                    parseId3Txxx(tagBody, dataStart, dataStart + frameSize)
                }
                decodedFrame?.let { (desc, value) -> acc.accept(desc, value) }
            }
            pos = dataStart + frameSize
        }
        return acc.build()
    }

    private fun removeId3Unsynchronization(bytes: ByteArray, start: Int, end: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream((end - start).coerceAtLeast(0))
        var i = start
        while (i < end) {
            val b = bytes[i].toInt() and 0xFF
            out.write(b)
            if (b == 0xFF && i + 1 < end && bytes[i + 1] == 0.toByte()) {
                i += 2
            } else {
                i += 1
            }
        }
        return out.toByteArray()
    }

    private fun parseId3Txxx(bytes: ByteArray, start: Int, end: Int): Pair<String, String>? {
        if (start >= end) return null
        val encoding = bytes[start].toInt() and 0xFF
        val charset = charsetForId3Encoding(encoding)
        val contentStart = start + 1
        val twoByte = encoding == 1 || encoding == 2
        var splitPos = contentStart
        var termLen = 0
        if (twoByte) {
            while (splitPos + 1 < end) {
                if (bytes[splitPos] == 0.toByte() && bytes[splitPos + 1] == 0.toByte()) {
                    termLen = 2
                    break
                }
                splitPos += 2
            }
        } else {
            while (splitPos < end) {
                if (bytes[splitPos] == 0.toByte()) {
                    termLen = 1
                    break
                }
                splitPos += 1
            }
        }
        if (termLen == 0 || splitPos + termLen > end) return null
        val desc = String(bytes, contentStart, splitPos - contentStart, charset).trim().trim('\u0000')
        val valueStart = splitPos + termLen
        val value = String(bytes, valueStart, end - valueStart, charset).trim().trim('\u0000')
        return desc to value
    }

    private fun parseOgg(bytes: ByteArray, len: Int): ReplayGainTags {
        val packet = java.io.ByteArrayOutputStream()
        var pos = 0
        var pagesRead = 0
        while (pos + 27 <= len && pagesRead < 16) {
            if (!matchesAscii(bytes, len, pos, "OggS")) break
            val segCount = bytes[pos + 26].toInt() and 0xFF
            val tableStart = pos + 27
            if (tableStart + segCount > len) break
            var bodyLen = 0
            for (i in 0 until segCount) {
                val lacing = bytes[tableStart + i].toInt() and 0xFF
                bodyLen += lacing
            }
            val bodyStart = tableStart + segCount
            if (bodyStart + bodyLen > len) break

            val headerType = bytes[pos + 5].toInt() and 0xFF
            val isContinued = (headerType and 0x01) != 0
            if (pagesRead > 0 && !isContinued) {
                // Evaluate previously accumulated packet if complete before starting next packet.
                val prev = packet.toByteArray()
                extractOggCommentPayload(prev)?.let { return it }
                packet.reset()
            }
            packet.write(bytes, bodyStart, bodyLen)
            pos = bodyStart + bodyLen
            pagesRead++
        }
        val tail = packet.toByteArray()
        return extractOggCommentPayload(tail) ?: ReplayGainTags()
    }

    private fun extractOggCommentPayload(packet: ByteArray): ReplayGainTags? {
        if (packet.size >= 7 &&
            packet[0] == 0x03.toByte() &&
            matchesAscii(packet, packet.size, 1, "vorbis")
        ) {
            return parseVorbisComments(packet, 7, packet.size)
        }
        if (packet.size >= 8 && matchesAscii(packet, packet.size, 0, "OpusTags")) {
            return parseVorbisComments(packet, 8, packet.size)
        }
        return null
    }

    private fun parseMp4TopLevel(bytes: ByteArray, len: Int): ReplayGainTags {
        var offset = 0
        while (offset + 8 <= len) {
            val rawSize = readUInt32Be(bytes, offset)
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            val headerLen: Int
            val boxSize: Long
            if (rawSize == 1L && offset + 16 <= len) {
                boxSize = readUInt64Be(bytes, offset + 8)
                headerLen = 16
            } else {
                boxSize = rawSize
                headerLen = 8
            }
            if (boxSize < headerLen || offset + boxSize > len) break
            if (type == "moov") {
                return parseMp4Container(bytes, offset + headerLen, (offset + boxSize).toInt())
            }
            offset += boxSize.toInt()
        }
        return ReplayGainTags()
    }

    private fun parseMp4Container(bytes: ByteArray, start: Int, end: Int): ReplayGainTags {
        val acc = TagAccumulator()
        findMp4Ilst(bytes, start, end, acc)
        return acc.build()
    }

    private fun findMp4Ilst(bytes: ByteArray, start: Int, end: Int, acc: TagAccumulator) {
        var pos = start
        while (pos + 8 <= end) {
            val size = readUInt32Be(bytes, pos).toInt()
            if (size < 8 || pos + size > end) break
            val type = String(bytes, pos + 4, 4, Charsets.US_ASCII)
            val childStart = pos + 8
            val childEnd = pos + size
            when (type) {
                "udta" -> findMp4Ilst(bytes, childStart, childEnd, acc)
                "meta" -> if (childStart + 4 <= childEnd) {
                    findMp4Ilst(bytes, childStart + 4, childEnd, acc)
                }
                "ilst" -> parseMp4IlstEntries(bytes, childStart, childEnd, acc)
            }
            pos += size
        }
    }

    private fun parseMp4IlstEntries(bytes: ByteArray, start: Int, end: Int, acc: TagAccumulator) {
        var pos = start
        while (pos + 8 <= end) {
            val size = readUInt32Be(bytes, pos).toInt()
            if (size < 8 || pos + size > end) break
            val type = String(bytes, pos + 4, 4, Charsets.US_ASCII)
            if (type == "----") {
                parseMp4Freeform(bytes, pos + 8, pos + size)?.let { (key, value) ->
                    acc.accept(key, value)
                }
            }
            pos += size
        }
    }

    private fun parseMp4Freeform(bytes: ByteArray, start: Int, end: Int): Pair<String, String>? {
        var pos = start
        var name: String? = null
        var dataVal: String? = null
        while (pos + 8 <= end) {
            val size = readUInt32Be(bytes, pos).toInt()
            if (size < 8 || pos + size > end) break
            val type = String(bytes, pos + 4, 4, Charsets.US_ASCII)
            val payloadStart = pos + 8
            val payloadEnd = pos + size
            if (type == "name" && payloadStart + 4 <= payloadEnd) {
                name = String(bytes, payloadStart + 4, payloadEnd - payloadStart - 4, Charsets.UTF_8).trim()
            } else if (type == "data" && payloadStart + 8 <= payloadEnd) {
                dataVal = String(bytes, payloadStart + 8, payloadEnd - payloadStart - 8, Charsets.UTF_8).trim()
            }
            pos += size
        }
        return if (!name.isNullOrBlank() && !dataVal.isNullOrBlank()) name to dataVal else null
    }

    private class TagAccumulator {
        private var rgTrackGain: Float? = null
        private var rgAlbumGain: Float? = null
        private var rgTrackPeak: Float? = null
        private var rgAlbumPeak: Float? = null
        private var r128TrackGain: Float? = null
        private var r128AlbumGain: Float? = null

        fun accept(rawKey: String, rawValue: String) {
            when (rawKey.trim().uppercase()) {
                "REPLAYGAIN_TRACK_GAIN" ->
                    rgTrackGain = rgTrackGain ?: ReplayGainCalculator.parseGainDb(rawValue)
                "REPLAYGAIN_ALBUM_GAIN" ->
                    rgAlbumGain = rgAlbumGain ?: ReplayGainCalculator.parseGainDb(rawValue)
                "REPLAYGAIN_TRACK_PEAK" ->
                    rgTrackPeak = rgTrackPeak ?: ReplayGainCalculator.parsePeak(rawValue)
                "REPLAYGAIN_ALBUM_PEAK" ->
                    rgAlbumPeak = rgAlbumPeak ?: ReplayGainCalculator.parsePeak(rawValue)
                "R128_TRACK_GAIN" ->
                    r128TrackGain = r128TrackGain ?: ReplayGainCalculator.parseR128GainDb(rawValue)
                "R128_ALBUM_GAIN" ->
                    r128AlbumGain = r128AlbumGain ?: ReplayGainCalculator.parseR128GainDb(rawValue)
            }
        }

        fun build(): ReplayGainTags = ReplayGainTags(
            trackGainDb = rgTrackGain ?: r128TrackGain,
            albumGainDb = rgAlbumGain ?: r128AlbumGain,
            trackPeak = rgTrackPeak,
            albumPeak = rgAlbumPeak,
        )
    }

    private fun charsetForId3Encoding(encoding: Int): Charset = when (encoding) {
        1 -> Charsets.UTF_16
        2 -> Charsets.UTF_16BE
        3 -> Charsets.UTF_8
        else -> Charsets.ISO_8859_1
    }

    private fun matchesAscii(bytes: ByteArray, length: Int, offset: Int, tag: String): Boolean {
        if (offset < 0 || offset + tag.length > length) return false
        for (i in tag.indices) {
            if (bytes[offset + i].toInt().toChar() != tag[i]) return false
        }
        return true
    }

    private fun decodeSynchsafe(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0x7F) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
            (bytes[offset + 3].toInt() and 0x7F)

    private fun readIntBe(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun readUInt32Be(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFFL) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 8) or
            (bytes[offset + 3].toLong() and 0xFFL)

    private fun readUInt64Be(bytes: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 0 until 8) {
            v = (v shl 8) or (bytes[offset + i].toLong() and 0xFFL)
        }
        return v
    }

    private fun readIntLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun readAscii4(buf: ByteBuffer): String {
        val a = CharArray(4) { (buf.get().toInt() and 0xFF).toChar() }
        return String(a)
    }

    private fun readFully(channel: SeekableByteChannel, buf: ByteBuffer): Int {
        var total = 0
        while (buf.hasRemaining()) {
            val n = channel.read(buf)
            if (n <= 0) break
            total += n
        }
        return total
    }

    private fun readBounded(stream: InputStream, maxBytes: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8_192)
        var total = 0
        while (total < maxBytes) {
            val n = stream.read(buf, 0, minOf(buf.size, maxBytes - total))
            if (n <= 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    private const val MAX_TAG_BYTES = 128 * 1024
    private const val MAX_FLAC_COMMENT_BYTES = 512 * 1024
    private const val MAX_ID3_UNSYNC_BYTES = 2 * 1024 * 1024
    private const val MAX_ID3_TXXX_BYTES = 16 * 1024
    private const val MAX_MP4_MOOV_BYTES = 8 * 1024 * 1024
}
