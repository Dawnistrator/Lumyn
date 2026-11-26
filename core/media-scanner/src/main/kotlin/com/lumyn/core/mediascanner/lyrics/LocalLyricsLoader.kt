package com.lumyn.core.mediascanner.lyrics

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.lumyn.core.model.FolderFilterConfig
import com.lumyn.core.model.LrcParser
import com.lumyn.core.model.ParsedLyrics
import com.lumyn.core.model.Track
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.InputStream
import java.nio.charset.Charset
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Resolves offline lyrics from:
 * 1. Sibling `.lrc` file (same folder & base filename via MediaStore)
 * 2. Embedded metadata (`LYRICS` / `UNSYNCEDLYRICS` Vorbis comments in FLAC, `USLT` frames in ID3v2)
 */
@Singleton
class LocalLyricsLoader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun loadLyrics(track: Track): ParsedLyrics? = withContext(Dispatchers.IO) {
        val siblingText = runCatching { readSiblingLrc(track) }.getOrNull()
        if (!siblingText.isNullOrBlank()) {
            LrcParser.parse(siblingText, sourceLabel = "Local .lrc file")?.let { return@withContext it }
        }

        val embeddedText = runCatching { readEmbeddedLyrics(track) }.getOrNull()
        if (!embeddedText.isNullOrBlank()) {
            LrcParser.parse(embeddedText, sourceLabel = "Embedded tags")?.let { return@withContext it }
        }

        null
    }

    private fun readSiblingLrc(track: Track): String? {
        val baseName = track.fileName.substringBeforeLast('.')
        if (baseName.isBlank()) return null
        val targetDisplayName = "$baseName.lrc"
        val normalizedTrackFolder = FolderFilterConfig.normalizeFolderPath(track.folderPath)

        val filesUri = MediaStore.Files.getContentUri("external")
        val pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.MediaColumns.RELATIVE_PATH
        } else {
            @Suppress("DEPRECATION")
            MediaStore.MediaColumns.DATA
        }
        val projection = arrayOf(MediaStore.MediaColumns._ID, pathColumn)

        context.contentResolver.query(
            filesUri,
            projection,
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(targetDisplayName),
            null,
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val pathIdx = cursor.getColumnIndex(pathColumn)
            var fallbackUri: Uri? = null

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIdx)
                val rowUri = ContentUris.withAppendedId(filesUri, id)
                val rawPath = if (pathIdx >= 0 && !cursor.isNull(pathIdx)) cursor.getString(pathIdx) else ""
                val folderOfRow = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    FolderFilterConfig.normalizeFolderPath(rawPath)
                } else {
                    FolderFilterConfig.normalizeFolderPath(rawPath.substringBeforeLast('/', ""))
                }
                if (normalizedTrackFolder.isEmpty() || folderOfRow.equals(normalizedTrackFolder, ignoreCase = true)) {
                    return readTextBounded(rowUri)
                }
                if (fallbackUri == null) fallbackUri = rowUri
            }
            if (fallbackUri != null) return readTextBounded(fallbackUri)
        }
        return null
    }

    private fun readTextBounded(uri: Uri): String? =
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val bytes = readBoundedBytes(stream, MAX_LYRIC_BYTES)
            if (bytes.isEmpty()) null else String(bytes, Charsets.UTF_8)
        }

    private fun readEmbeddedLyrics(track: Track): String? {
        val uri = Uri.parse(track.contentUri)
        return context.contentResolver.openInputStream(uri)?.use { stream ->
            val header = readBoundedBytes(stream, MAX_EMBEDDED_HEADER_BYTES)
            when {
                header.size >= 4 &&
                    header[0] == 'f'.code.toByte() &&
                    header[1] == 'L'.code.toByte() &&
                    header[2] == 'a'.code.toByte() &&
                    header[3] == 'C'.code.toByte() -> extractFlacVorbisLyrics(header)

                header.size >= 10 &&
                    header[0] == 'I'.code.toByte() &&
                    header[1] == 'D'.code.toByte() &&
                    header[2] == '3'.code.toByte() -> extractId3UsltLyrics(header)

                else -> null
            }
        }
    }

    private fun extractFlacVorbisLyrics(bytes: ByteArray): String? {
        var offset = 4
        while (offset + 4 <= bytes.size) {
            val headerByte = bytes[offset].toInt() and 0xFF
            val isLastBlock = (headerByte and 0x80) != 0
            val blockType = headerByte and 0x7F
            val length = ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
            val dataStart = offset + 4
            if (blockType == 4 && dataStart + length <= bytes.size) {
                return parseVorbisComments(bytes, dataStart, dataStart + length)
            }
            offset = dataStart + length
            if (isLastBlock || length < 0) break
        }
        return null
    }

    private fun parseVorbisComments(bytes: ByteArray, start: Int, end: Int): String? {
        var pos = start
        if (pos + 4 > end) return null
        val vendorLen = readIntLe(bytes, pos)
        pos += 4 + vendorLen.coerceAtLeast(0)
        if (pos + 4 > end) return null
        val commentCount = readIntLe(bytes, pos).coerceIn(0, 512)
        pos += 4

        var unsyncedFallback: String? = null
        repeat(commentCount) {
            if (pos + 4 > end) return@repeat
            val len = readIntLe(bytes, pos)
            pos += 4
            if (len < 0 || pos + len > end) return unsyncedFallback
            val entry = String(bytes, pos, len, Charsets.UTF_8)
            pos += len
            val eqIdx = entry.indexOf('=')
            if (eqIdx > 0) {
                val key = entry.substring(0, eqIdx).trim().uppercase()
                val value = entry.substring(eqIdx + 1).trim()
                if (value.isNotEmpty()) {
                    if (key == "LYRICS") return value
                    if (key == "UNSYNCEDLYRICS" && unsyncedFallback == null) unsyncedFallback = value
                }
            }
        }
        return unsyncedFallback
    }

    private fun extractId3UsltLyrics(bytes: ByteArray): String? {
        if (bytes.size < 10) return null
        val majorVersion = bytes[3].toInt() and 0xFF
        val flags = bytes[5].toInt() and 0xFF
        val tagSize = decodeSynchsafe(bytes, 6)
        val tagEnd = (10 + tagSize).coerceAtMost(bytes.size)
        var pos = 10

        // Skip optional extended header in ID3v2.3 / v2.4.
        if ((flags and 0x40) != 0 && pos + 4 <= tagEnd) {
            val extSize = if (majorVersion >= 4) decodeSynchsafe(bytes, pos) else readIntBe(bytes, pos)
            pos += extSize.coerceAtLeast(4)
        }

        val headerLen = if (majorVersion >= 3) 10 else return null
        while (pos + headerLen <= tagEnd) {
            val frameId = String(bytes, pos, 4, Charsets.US_ASCII)
            if (frameId.all { it == '\u0000' }) break
            val frameSize = if (majorVersion >= 4) decodeSynchsafe(bytes, pos + 4) else readIntBe(bytes, pos + 4)
            val dataStart = pos + headerLen
            if (frameSize <= 0 || dataStart + frameSize > tagEnd) break
            if (frameId == "USLT") {
                val parsed = decodeUsltFrame(bytes, dataStart, dataStart + frameSize)
                if (!parsed.isNullOrBlank()) return parsed
            }
            pos = dataStart + frameSize
        }
        return null
    }

    private fun decodeUsltFrame(bytes: ByteArray, start: Int, end: Int): String? {
        if (end - start < 5) return null
        val encoding = bytes[start].toInt() and 0xFF
        // Bytes start+1..start+3 contain 3-letter ISO-639-2 language code.
        var pos = start + 4
        val charset = when (encoding) {
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        val twoByteNull = encoding == 1 || encoding == 2
        if (twoByteNull) {
            while (pos + 1 < end) {
                if (bytes[pos] == 0.toByte() && bytes[pos + 1] == 0.toByte()) {
                    pos += 2
                    break
                }
                pos += 2
            }
        } else {
            while (pos < end) {
                if (bytes[pos] == 0.toByte()) {
                    pos += 1
                    break
                }
                pos += 1
            }
        }
        if (pos >= end) return null
        return decodeTextSafe(bytes, pos, end - pos, charset).trim().trim('\u0000')
    }

    private fun decodeTextSafe(bytes: ByteArray, offset: Int, length: Int, charset: Charset): String =
        String(bytes, offset, length, charset)

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

    private fun readIntLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun readBoundedBytes(stream: InputStream, maxBytes: Int): ByteArray {
        val chunk = ByteArray(8_192)
        var total = 0
        val out = java.io.ByteArrayOutputStream()
        while (total < maxBytes) {
            val read = stream.read(chunk, 0, minOf(chunk.size, maxBytes - total))
            if (read <= 0) break
            out.write(chunk, 0, read)
            total += read
        }
        return out.toByteArray()
    }

    private companion object {
        const val MAX_LYRIC_BYTES = 256 * 1024
        const val MAX_EMBEDDED_HEADER_BYTES = 256 * 1024
    }
}
