package com.lumyn.core.mediascanner.tags

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.charset.Charset

data class FileTagPatch(
    val title: String,
    val artist: String,
    val album: String,
    val trackNumber: Int,
    val year: Int,
    val genre: String,
    val coverArtJpegBytes: ByteArray? = null,
)

enum class FileTagWriteOutcome {
    WRITTEN,
    UNSUPPORTED_FORMAT,
    FAILED,
}

/**
 * Pure-Kotlin streaming audio tag writer for FLAC (Vorbis Comment + PICTURE),
 * MP3 (ID3v2.3 / ID3v2.4 frames + APIC), and MP4/M4A (`ilst` atoms with `stco`/`co64` offset shifting).
 * Audio payloads are copied in fixed chunks without loading large files into heap memory.
 */
object AudioFileTagWriter {

    fun write(inputFile: File, outputFile: File, patch: FileTagPatch): FileTagWriteOutcome {
        if (!inputFile.exists() || inputFile.length() < 12L) return FileTagWriteOutcome.FAILED
        val magic = ByteArray(12)
        val readMagic = FileInputStream(inputFile).use { it.read(magic) }
        if (readMagic < 4) return FileTagWriteOutcome.FAILED

        return runCatching {
            when {
                matchesAscii(magic, readMagic, 0, "fLaC") -> writeFlac(inputFile, outputFile, patch)
                matchesAscii(magic, readMagic, 0, "ID3") -> writeMp3(inputFile, outputFile, patch)
                readMagic >= 8 && matchesAscii(magic, readMagic, 4, "ftyp") -> writeMp4(inputFile, outputFile, patch)
                isLikelyMp3FrameSync(magic, readMagic) -> writeMp3(inputFile, outputFile, patch)
                else -> FileTagWriteOutcome.UNSUPPORTED_FORMAT
            }
        }.getOrElse { FileTagWriteOutcome.FAILED }
    }

    private fun writeFlac(inputFile: File, outputFile: File, patch: FileTagPatch): FileTagWriteOutcome {
        var vendorString = "Lumyn"
        val preservedComments = mutableListOf<String>()
        var streamInfoBytes: ByteArray? = null
        val otherBlocks = mutableListOf<Pair<Int, ByteArray>>()
        var audioStartOffset = -1L

        FileInputStream(inputFile).use { fis ->
            val sig = ByteArray(4)
            if (readFully(fis, sig) < 4 || !matchesAscii(sig, 4, 0, "fLaC")) {
                return FileTagWriteOutcome.UNSUPPORTED_FORMAT
            }
            var offset = 4L
            val hdr = ByteArray(4)
            while (true) {
                if (readFully(fis, hdr) < 4) return FileTagWriteOutcome.FAILED
                val headerByte = hdr[0].toInt() and 0xFF
                val isLast = (headerByte and 0x80) != 0
                val blockType = headerByte and 0x7F
                val blockLen = ((hdr[1].toInt() and 0xFF) shl 16) or
                    ((hdr[2].toInt() and 0xFF) shl 8) or
                    (hdr[3].toInt() and 0xFF)
                val data = ByteArray(blockLen)
                if (readFully(fis, data) < blockLen) return FileTagWriteOutcome.FAILED
                offset += 4L + blockLen

                when (blockType) {
                    0 -> streamInfoBytes = data
                    1 -> Unit // Drop old padding blocks; keep file compact cleanly.
                    4 -> {
                        val parsed = decodeVorbisCommentBlock(data)
                        if (parsed.first.isNotBlank()) vendorString = parsed.first
                        parsed.second.forEach { entry ->
                            val key = entry.substringBefore('=').trim().uppercase()
                            if (key !in MANAGED_VORBIS_KEYS) {
                                preservedComments += entry
                            }
                        }
                    }
                    6 -> {
                        if (patch.coverArtJpegBytes == null) {
                            otherBlocks += blockType to data
                        }
                    }
                    else -> otherBlocks += blockType to data
                }
                if (isLast) {
                    audioStartOffset = offset
                    break
                }
            }
        }

        val streamInfo = streamInfoBytes ?: return FileTagWriteOutcome.FAILED
        if (audioStartOffset < 0L) return FileTagWriteOutcome.FAILED

        val newComments = ArrayList<String>(preservedComments.size + 6)
        newComments.addAll(preservedComments)
        newComments += "TITLE=${patch.title}"
        newComments += "ARTIST=${patch.artist}"
        newComments += "ALBUM=${patch.album}"
        if (patch.trackNumber > 0) newComments += "TRACKNUMBER=${patch.trackNumber}"
        if (patch.year > 0) newComments += "DATE=${patch.year}"
        if (patch.genre.isNotBlank()) newComments += "GENRE=${patch.genre}"

        val vorbisPayload = encodeVorbisCommentBlock(vendorString, newComments)
        val blocksToWrite = mutableListOf<Pair<Int, ByteArray>>()
        blocksToWrite += 0 to streamInfo
        blocksToWrite += 4 to vorbisPayload
        patch.coverArtJpegBytes?.let { jpeg ->
            if (jpeg.size < MAX_FLAC_PICTURE_BYTES) {
                blocksToWrite += 6 to encodeFlacPictureBlock(jpeg)
            }
        }
        blocksToWrite.addAll(otherBlocks)

        FileOutputStream(outputFile).use { fos ->
            fos.write("fLaC".toByteArray(Charsets.US_ASCII))
            blocksToWrite.forEachIndexed { index, (type, payload) ->
                if (payload.size > 0x00FF_FFFF) return FileTagWriteOutcome.FAILED
                val isLast = index == blocksToWrite.lastIndex
                val firstByte = (type and 0x7F) or (if (isLast) 0x80 else 0)
                fos.write(firstByte)
                fos.write((payload.size ushr 16) and 0xFF)
                fos.write((payload.size ushr 8) and 0xFF)
                fos.write(payload.size and 0xFF)
                fos.write(payload)
            }
            copyTail(inputFile, audioStartOffset, fos)
        }
        return FileTagWriteOutcome.WRITTEN
    }

    private fun decodeVorbisCommentBlock(bytes: ByteArray): Pair<String, List<String>> {
        if (bytes.size < 8) return "" to emptyList()
        var pos = 0
        val vendorLen = readIntLe(bytes, pos)
        pos += 4
        if (vendorLen < 0 || pos + vendorLen > bytes.size) return "" to emptyList()
        val vendor = String(bytes, pos, vendorLen, Charsets.UTF_8)
        pos += vendorLen
        if (pos + 4 > bytes.size) return vendor to emptyList()
        val count = readIntLe(bytes, pos).coerceIn(0, 2048)
        pos += 4
        val comments = mutableListOf<String>()
        repeat(count) {
            if (pos + 4 > bytes.size) return vendor to comments
            val len = readIntLe(bytes, pos)
            pos += 4
            if (len < 0 || pos + len > bytes.size) return vendor to comments
            comments += String(bytes, pos, len, Charsets.UTF_8)
            pos += len
        }
        return vendor to comments
    }

    private fun encodeVorbisCommentBlock(vendor: String, comments: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        val vendorBytes = vendor.toByteArray(Charsets.UTF_8)
        writeIntLe(out, vendorBytes.size)
        out.write(vendorBytes)
        writeIntLe(out, comments.size)
        comments.forEach { entry ->
            val b = entry.toByteArray(Charsets.UTF_8)
            writeIntLe(out, b.size)
            out.write(b)
        }
        return out.toByteArray()
    }

    private fun encodeFlacPictureBlock(jpegBytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(jpegBytes.size + 64)
        val mime = "image/jpeg".toByteArray(Charsets.US_ASCII)
        writeIntBe(out, 3) // Cover (front)
        writeIntBe(out, mime.size)
        out.write(mime)
        writeIntBe(out, 0) // Empty description
        writeIntBe(out, 0)
        writeIntBe(out, 0)
        writeIntBe(out, 24)
        writeIntBe(out, 0)
        writeIntBe(out, jpegBytes.size)
        out.write(jpegBytes)
        return out.toByteArray()
    }

    private data class RawId3Frame(
        val id: String,
        val flag0: Int,
        val flag1: Int,
        val payload: ByteArray,
    )

    private fun writeMp3(inputFile: File, outputFile: File, patch: FileTagPatch): FileTagWriteOutcome {
        var targetMajorVersion = 3
        var audioStartOffset = 0L
        val preservedFrames = mutableListOf<RawId3Frame>()

        FileInputStream(inputFile).use { fis ->
            val header = ByteArray(10)
            if (readFully(fis, header) == 10 && matchesAscii(header, 10, 0, "ID3")) {
                val major = header[3].toInt() and 0xFF
                val flags = header[5].toInt() and 0xFF
                val isUnsync = (flags and 0x80) != 0
                val tagSize = decodeSynchsafe(header, 6)
                audioStartOffset = (10L + tagSize).coerceAtMost(inputFile.length())
                if (major in 3..4 && tagSize in 0..MAX_ID3_TAG_BYTES) {
                    targetMajorVersion = major
                    val rawTagBody = ByteArray(tagSize)
                    val rawRead = readFully(fis, rawTagBody)
                    val tagBody = if (isUnsync) {
                        removeId3Unsynchronization(rawTagBody, rawRead)
                    } else {
                        rawTagBody
                    }
                    val readBody = if (isUnsync) tagBody.size else rawRead
                    var pos = 0
                    if ((flags and 0x40) != 0 && pos + 4 <= readBody) {
                        val extSize = if (major >= 4) decodeSynchsafe(tagBody, pos) else readIntBe(tagBody, pos)
                        pos += if (major >= 4) extSize.coerceAtLeast(4) else 4 + extSize.coerceAtLeast(0)
                    }
                    while (pos + 10 <= readBody) {
                        val frameId = String(tagBody, pos, 4, Charsets.US_ASCII)
                        if (frameId.all { it == '\u0000' }) break
                        val frameSize = if (major >= 4) {
                            decodeSynchsafe(tagBody, pos + 4)
                        } else {
                            readIntBe(tagBody, pos + 4)
                        }
                        val flag0 = tagBody[pos + 8].toInt() and 0xFF
                        val rawFlag1 = tagBody[pos + 9].toInt() and 0xFF
                        val flag1 = if (isUnsync && major >= 4) (rawFlag1 and 0x02.inv()) else rawFlag1
                        val payloadStart = pos + 10
                        if (frameSize <= 0 || payloadStart + frameSize > readBody) break
                        val shouldReplace = frameId in MANAGED_ID3_FRAMES ||
                            (frameId == "APIC" && patch.coverArtJpegBytes != null)
                        if (!shouldReplace) {
                            preservedFrames += RawId3Frame(
                                id = frameId,
                                flag0 = flag0,
                                flag1 = flag1,
                                payload = tagBody.copyOfRange(payloadStart, payloadStart + frameSize),
                            )
                        }
                        pos = payloadStart + frameSize
                    }
                }
            }
        }

        val allFrames = ArrayList<RawId3Frame>(preservedFrames.size + 7)
        allFrames += RawId3Frame("TIT2", 0, 0, encodeId3TextPayload(patch.title, targetMajorVersion))
        allFrames += RawId3Frame("TPE1", 0, 0, encodeId3TextPayload(patch.artist, targetMajorVersion))
        allFrames += RawId3Frame("TALB", 0, 0, encodeId3TextPayload(patch.album, targetMajorVersion))
        if (patch.trackNumber > 0) {
            allFrames += RawId3Frame("TRCK", 0, 0, encodeId3TextPayload(patch.trackNumber.toString(), targetMajorVersion))
        }
        if (patch.year > 0) {
            val yearFrame = if (targetMajorVersion >= 4) "TDRC" else "TYER"
            allFrames += RawId3Frame(yearFrame, 0, 0, encodeId3TextPayload(patch.year.toString(), targetMajorVersion))
        }
        if (patch.genre.isNotBlank()) {
            allFrames += RawId3Frame("TCON", 0, 0, encodeId3TextPayload(patch.genre, targetMajorVersion))
        }
        patch.coverArtJpegBytes?.let { jpeg ->
            allFrames += RawId3Frame("APIC", 0, 0, encodeId3ApicPayload(jpeg))
        }
        allFrames.addAll(preservedFrames)

        val bodyStream = ByteArrayOutputStream()
        allFrames.forEach { frame ->
            val idBytes = frame.id.padEnd(4, ' ').take(4).toByteArray(Charsets.US_ASCII)
            bodyStream.write(idBytes)
            if (targetMajorVersion >= 4) {
                writeSynchsafe(bodyStream, frame.payload.size)
            } else {
                writeIntBe(bodyStream, frame.payload.size)
            }
            bodyStream.write(frame.flag0 and 0xFF)
            bodyStream.write(frame.flag1 and 0xFF)
            bodyStream.write(frame.payload)
        }
        val tagBodyBytes = bodyStream.toByteArray()

        FileOutputStream(outputFile).use { fos ->
            fos.write("ID3".toByteArray(Charsets.US_ASCII))
            fos.write(targetMajorVersion)
            fos.write(0) // minor revision
            fos.write(0) // flags
            writeSynchsafe(fos, tagBodyBytes.size)
            fos.write(tagBodyBytes)
            copyTail(inputFile, audioStartOffset, fos)
        }
        return FileTagWriteOutcome.WRITTEN
    }

    private fun removeId3Unsynchronization(bytes: ByteArray, length: Int): ByteArray {
        val out = ByteArrayOutputStream(length)
        var i = 0
        while (i < length) {
            val b = bytes[i].toInt() and 0xFF
            out.write(b)
            if (b == 0xFF && i + 1 < length && bytes[i + 1] == 0.toByte()) {
                i += 2
            } else {
                i += 1
            }
        }
        return out.toByteArray()
    }

    private fun encodeId3TextPayload(text: String, majorVersion: Int): ByteArray {
        val out = ByteArrayOutputStream()
        if (majorVersion >= 4) {
            out.write(3) // UTF-8
            out.write(text.toByteArray(Charsets.UTF_8))
        } else {
            out.write(1) // UTF-16 with BOM
            out.write(text.toByteArray(Charsets.UTF_16))
        }
        return out.toByteArray()
    }

    private fun encodeId3ApicPayload(jpegBytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(jpegBytes.size + 32)
        out.write(0) // ISO-8859-1 MIME + description
        out.write("image/jpeg".toByteArray(Charsets.US_ASCII))
        out.write(0) // null-terminated MIME
        out.write(3) // Front cover
        out.write(0) // Empty null-terminated description
        out.write(jpegBytes)
        return out.toByteArray()
    }

    private fun writeMp4(inputFile: File, outputFile: File, patch: FileTagPatch): FileTagWriteOutcome {
        data class TopBox(val type: String, val offset: Long, val size: Long, val headerSize: Int)

        val topBoxes = mutableListOf<TopBox>()
        RandomAccessFile(inputFile, "r").use { raf ->
            val fileLen = raf.length()
            var pos = 0L
            val hdr = ByteArray(16)
            while (pos + 8L <= fileLen) {
                raf.seek(pos)
                if (raf.read(hdr, 0, 8) < 8) return FileTagWriteOutcome.FAILED
                val rawSize = readUInt32Be(hdr, 0)
                val type = String(hdr, 4, 4, FOURCC_CHARSET)
                val headerSize: Int
                val boxSize: Long
                if (rawSize == 1L) {
                    if (pos + 16L > fileLen) return FileTagWriteOutcome.FAILED
                    if (raf.read(hdr, 8, 8) < 8) return FileTagWriteOutcome.FAILED
                    boxSize = readUInt64Be(hdr, 8)
                    headerSize = 16
                } else if (rawSize == 0L) {
                    boxSize = fileLen - pos
                    headerSize = 8
                } else {
                    boxSize = rawSize
                    headerSize = 8
                }
                if (boxSize < headerSize || pos + boxSize > fileLen) return FileTagWriteOutcome.FAILED
                topBoxes += TopBox(type = type, offset = pos, size = boxSize, headerSize = headerSize)
                pos += boxSize
            }
        }

        val moovBox = topBoxes.firstOrNull { it.type == "moov" } ?: return FileTagWriteOutcome.UNSUPPORTED_FORMAT
        if (moovBox.size > MAX_MP4_MOOV_BYTES) return FileTagWriteOutcome.FAILED
        val originalMoovEnd = moovBox.offset + moovBox.size

        val oldMoovBytes = ByteArray(moovBox.size.toInt())
        RandomAccessFile(inputFile, "r").use { raf ->
            raf.seek(moovBox.offset)
            raf.readFully(oldMoovBytes)
        }

        val newMoovBytes = rebuildMp4Moov(oldMoovBytes, moovBox.headerSize, patch)
            ?: return FileTagWriteOutcome.FAILED

        val delta = newMoovBytes.size.toLong() - moovBox.size
        if (delta != 0L) {
            if (!shiftMp4ChunkOffsets(newMoovBytes, 8, newMoovBytes.size, originalMoovEnd, delta)) {
                return FileTagWriteOutcome.FAILED
            }
        }

        FileOutputStream(outputFile).use { fos ->
            RandomAccessFile(inputFile, "r").use { raf ->
                topBoxes.forEach { box ->
                    if (box.offset == moovBox.offset) {
                        fos.write(newMoovBytes)
                    } else {
                        copyRange(raf, box.offset, box.size, fos)
                    }
                }
            }
        }
        return FileTagWriteOutcome.WRITTEN
    }

    private fun rebuildMp4Moov(oldMoov: ByteArray, moovHeaderLen: Int, patch: FileTagPatch): ByteArray? {
        val moovChildren = splitMp4ChildBoxes(oldMoov, moovHeaderLen, oldMoov.size) ?: return null
        val otherMoovChildren = mutableListOf<ByteArray>()
        var existingUdta: ByteArray? = null
        moovChildren.forEach { child ->
            if (boxType(child) == "udta") existingUdta = child else otherMoovChildren += child
        }

        val otherUdtaChildren = mutableListOf<ByteArray>()
        var existingMeta: ByteArray? = null
        existingUdta?.let { udta ->
            splitMp4ChildBoxes(udta, 8, udta.size)?.forEach { child ->
                if (boxType(child) == "meta") existingMeta = child else otherUdtaChildren += child
            }
        }

        val otherMetaChildren = mutableListOf<ByteArray>()
        var existingIlst: ByteArray? = null
        existingMeta?.let { meta ->
            if (meta.size >= 12) {
                splitMp4ChildBoxes(meta, 12, meta.size)?.forEach { child ->
                    if (boxType(child) == "ilst") existingIlst = child else otherMetaChildren += child
                }
            }
        }

        val preservedIlstEntries = mutableListOf<ByteArray>()
        existingIlst?.let { ilst ->
            splitMp4ChildBoxes(ilst, 8, ilst.size)?.forEach { entry ->
                val type = boxType(entry)
                val shouldReplace = type in MANAGED_MP4_ILST_TYPES ||
                    (type == "covr" && patch.coverArtJpegBytes != null)
                if (!shouldReplace) {
                    preservedIlstEntries += entry
                }
            }
        }

        val ilstEntries = ArrayList<ByteArray>(preservedIlstEntries.size + 7)
        ilstEntries += buildMp4TextIlstAtom("\u00A9nam", patch.title)
        ilstEntries += buildMp4TextIlstAtom("\u00A9ART", patch.artist)
        ilstEntries += buildMp4TextIlstAtom("\u00A9alb", patch.album)
        if (patch.trackNumber > 0) {
            ilstEntries += buildMp4TrackNumberAtom(patch.trackNumber)
        }
        if (patch.year > 0) {
            ilstEntries += buildMp4TextIlstAtom("\u00A9day", patch.year.toString())
        }
        if (patch.genre.isNotBlank()) {
            ilstEntries += buildMp4TextIlstAtom("\u00A9gen", patch.genre)
        }
        patch.coverArtJpegBytes?.let { jpeg ->
            ilstEntries += buildMp4CoverAtom(jpeg)
        }
        ilstEntries.addAll(preservedIlstEntries)

        val newIlst = buildMp4Box("ilst", ilstEntries)
        val metaChildren = if (otherMetaChildren.none { boxType(it) == "hdlr" }) {
            listOf(buildMp4MdirHdlrBox()) + otherMetaChildren + listOf(newIlst)
        } else {
            otherMetaChildren + listOf(newIlst)
        }
        val newMeta = buildMp4FullBox("meta", metaChildren)
        val newUdta = buildMp4Box("udta", otherUdtaChildren + listOf(newMeta))
        return buildMp4Box("moov", otherMoovChildren + listOf(newUdta))
    }

    private fun buildMp4MdirHdlrBox(): ByteArray {
        val out = ByteArrayOutputStream(21)
        writeIntBe(out, 0) // pre_defined
        out.write("mdir".toByteArray(FOURCC_CHARSET), 0, 4)
        out.write("appl".toByteArray(FOURCC_CHARSET), 0, 4)
        writeIntBe(out, 0)
        writeIntBe(out, 0)
        out.write(0) // empty null-terminated handler name
        return buildMp4FullBox("hdlr", listOf(out.toByteArray()))
    }

    private fun splitMp4ChildBoxes(bytes: ByteArray, start: Int, end: Int): List<ByteArray>? {
        val result = mutableListOf<ByteArray>()
        var pos = start
        while (pos + 8 <= end) {
            val size = readUInt32Be(bytes, pos).toInt()
            if (size < 8 || pos + size > end) return null
            result += bytes.copyOfRange(pos, pos + size)
            pos += size
        }
        return result
    }

    private fun buildMp4Box(type: String, children: List<ByteArray>): ByteArray {
        val totalSize = 8 + children.sumOf { it.size }
        val out = ByteArrayOutputStream(totalSize)
        writeIntBe(out, totalSize)
        out.write(type.toByteArray(FOURCC_CHARSET), 0, 4)
        children.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun buildMp4FullBox(type: String, children: List<ByteArray>): ByteArray {
        val totalSize = 12 + children.sumOf { it.size }
        val out = ByteArrayOutputStream(totalSize)
        writeIntBe(out, totalSize)
        out.write(type.toByteArray(FOURCC_CHARSET), 0, 4)
        writeIntBe(out, 0) // version + flags
        children.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun buildMp4TextIlstAtom(fourCc: String, value: String): ByteArray {
        val utf8 = value.toByteArray(Charsets.UTF_8)
        val dataSize = 16 + utf8.size
        val dataOut = ByteArrayOutputStream(dataSize)
        writeIntBe(dataOut, dataSize)
        dataOut.write("data".toByteArray(FOURCC_CHARSET))
        writeIntBe(dataOut, 1) // UTF-8 string
        writeIntBe(dataOut, 0) // locale
        dataOut.write(utf8)
        return buildMp4Box(fourCc, listOf(dataOut.toByteArray()))
    }

    private fun buildMp4TrackNumberAtom(trackNumber: Int): ByteArray {
        val payload = byteArrayOf(
            0, 0,
            ((trackNumber ushr 8) and 0xFF).toByte(),
            (trackNumber and 0xFF).toByte(),
            0, 0, 0, 0,
        )
        val dataSize = 16 + payload.size
        val dataOut = ByteArrayOutputStream(dataSize)
        writeIntBe(dataOut, dataSize)
        dataOut.write("data".toByteArray(FOURCC_CHARSET))
        writeIntBe(dataOut, 0) // binary integer pair
        writeIntBe(dataOut, 0)
        dataOut.write(payload)
        return buildMp4Box("trkn", listOf(dataOut.toByteArray()))
    }

    private fun buildMp4CoverAtom(jpegBytes: ByteArray): ByteArray {
        val dataSize = 16 + jpegBytes.size
        val dataOut = ByteArrayOutputStream(dataSize)
        writeIntBe(dataOut, dataSize)
        dataOut.write("data".toByteArray(FOURCC_CHARSET))
        writeIntBe(dataOut, 13) // JPEG
        writeIntBe(dataOut, 0)
        dataOut.write(jpegBytes)
        return buildMp4Box("covr", listOf(dataOut.toByteArray()))
    }

    private fun shiftMp4ChunkOffsets(
        moov: ByteArray,
        start: Int,
        end: Int,
        originalMoovEnd: Long,
        delta: Long,
    ): Boolean {
        var pos = start
        while (pos + 8 <= end) {
            val size = readUInt32Be(moov, pos).toInt()
            if (size < 8 || pos + size > end) return false
            val type = String(moov, pos + 4, 4, FOURCC_CHARSET)
            val childStart = pos + 8
            val childEnd = pos + size
            when (type) {
                "trak", "mdia", "minf", "stbl" -> {
                    if (!shiftMp4ChunkOffsets(moov, childStart, childEnd, originalMoovEnd, delta)) return false
                }
                "stco" -> {
                    if (childStart + 8 > childEnd) return false
                    val entryCount = readUInt32Be(moov, childStart + 4).toInt()
                    var entryPos = childStart + 8
                    if (entryCount < 0 || entryPos + entryCount * 4L > childEnd) return false
                    repeat(entryCount) {
                        val oldOff = readUInt32Be(moov, entryPos)
                        if (oldOff >= originalMoovEnd) {
                            val newOff = oldOff + delta
                            if (newOff < 0L || newOff > 0xFFFF_FFFFL) return false
                            putIntBe(moov, entryPos, newOff.toInt())
                        }
                        entryPos += 4
                    }
                }
                "co64" -> {
                    if (childStart + 8 > childEnd) return false
                    val entryCount = readUInt32Be(moov, childStart + 4).toInt()
                    var entryPos = childStart + 8
                    if (entryCount < 0 || entryPos + entryCount * 8L > childEnd) return false
                    repeat(entryCount) {
                        val oldOff = readUInt64Be(moov, entryPos)
                        if (oldOff >= originalMoovEnd) {
                            val newOff = oldOff + delta
                            if (newOff < 0L) return false
                            putLongBe(moov, entryPos, newOff)
                        }
                        entryPos += 8
                    }
                }
            }
            pos += size
        }
        return true
    }

    private fun boxType(box: ByteArray): String =
        if (box.size >= 8) String(box, 4, 4, FOURCC_CHARSET) else ""

    private fun copyTail(inputFile: File, startOffset: Long, fos: OutputStream) {
        RandomAccessFile(inputFile, "r").use { raf ->
            val len = raf.length() - startOffset
            if (len > 0L) copyRange(raf, startOffset, len, fos)
        }
    }

    private fun copyRange(raf: RandomAccessFile, offset: Long, length: Long, fos: OutputStream) {
        raf.seek(offset)
        val buf = ByteArray(64 * 1024)
        var remaining = length
        while (remaining > 0L) {
            val toRead = minOf(buf.size.toLong(), remaining).toInt()
            val n = raf.read(buf, 0, toRead)
            if (n <= 0) break
            fos.write(buf, 0, n)
            remaining -= n
        }
    }

    private fun isLikelyMp3FrameSync(bytes: ByteArray, length: Int): Boolean =
        length >= 2 &&
            (bytes[0].toInt() and 0xFF) == 0xFF &&
            (bytes[1].toInt() and 0xE0) == 0xE0

    private fun matchesAscii(bytes: ByteArray, length: Int, offset: Int, tag: String): Boolean {
        if (offset < 0 || offset + tag.length > length) return false
        for (i in tag.indices) {
            if (bytes[offset + i].toInt().toChar() != tag[i]) return false
        }
        return true
    }

    private fun readFully(stream: InputStream, target: ByteArray): Int {
        var total = 0
        while (total < target.size) {
            val n = stream.read(target, total, target.size - total)
            if (n <= 0) break
            total += n
        }
        return total
    }

    private fun decodeSynchsafe(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0x7F) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
            (bytes[offset + 3].toInt() and 0x7F)

    private fun writeSynchsafe(out: OutputStream, value: Int) {
        out.write((value ushr 21) and 0x7F)
        out.write((value ushr 14) and 0x7F)
        out.write((value ushr 7) and 0x7F)
        out.write(value and 0x7F)
    }

    private fun readIntBe(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun readUInt32Be(bytes: ByteArray, offset: Int): Long =
        readIntBe(bytes, offset).toLong() and 0xFFFF_FFFFL

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

    private fun writeIntBe(out: OutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun putIntBe(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = ((value ushr 24) and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        bytes[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 3] = (value and 0xFF).toByte()
    }

    private fun putLongBe(bytes: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) {
            bytes[offset + i] = ((value ushr (56 - i * 8)) and 0xFFL).toByte()
        }
    }

    private fun writeIntLe(out: OutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 24) and 0xFF)
    }

    private val FOURCC_CHARSET: Charset = Charsets.ISO_8859_1
    private val MANAGED_VORBIS_KEYS = setOf("TITLE", "ARTIST", "ALBUM", "TRACKNUMBER", "DATE", "GENRE")
    private val MANAGED_ID3_FRAMES = setOf("TIT2", "TPE1", "TALB", "TRCK", "TYER", "TDRC", "TCON")
    private val MANAGED_MP4_ILST_TYPES = setOf("\u00A9nam", "\u00A9ART", "\u00A9alb", "trkn", "\u00A9day", "\u00A9gen")
    private const val MAX_ID3_TAG_BYTES = 32 * 1024 * 1024
    private const val MAX_FLAC_PICTURE_BYTES = 16 * 1024 * 1024
    private const val MAX_MP4_MOOV_BYTES = 16L * 1024L * 1024L
}
