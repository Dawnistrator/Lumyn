package com.lunaria.core.mediascanner.metadata

import java.io.InputStream
import kotlin.math.pow
import kotlin.math.roundToInt

data class ParsedAudioHeader(
    val codec: String,
    val bitDepth: Int,
    val sampleRateHz: Int,
    val channelCount: Int,
    val isLossless: Boolean,
)

/**
 * Pure-Kotlin binary header inspector for uncompressed & lossless container formats.
 * Reads sample rate, bit depth and channels directly from FLAC, WAV, AIFF and DSF headers
 * so Hi-Res metadata stays exact even on API 26–30 where MediaStore omits bit depth.
 */
object AudioHeaderInspector {

    private const val MaxHeaderBytes = 4096

    fun inspectStream(input: InputStream): ParsedAudioHeader? {
        val buffer = ByteArray(MaxHeaderBytes)
        var totalRead = 0
        while (totalRead < buffer.size) {
            val read = input.read(buffer, totalRead, buffer.size - totalRead)
            if (read <= 0) break
            totalRead += read
        }
        return if (totalRead > 0) inspectHeader(buffer, totalRead) else null
    }

    fun inspectHeader(bytes: ByteArray, length: Int = bytes.size): ParsedAudioHeader? {
        val usableLength = minOf(length, bytes.size)
        return when {
            matchesAscii(bytes, usableLength, 0, "fLaC") -> parseFlac(bytes, usableLength)
            matchesAscii(bytes, usableLength, 0, "RIFF") &&
                matchesAscii(bytes, usableLength, 8, "WAVE") -> parseWav(bytes, usableLength)

            matchesAscii(bytes, usableLength, 0, "FORM") &&
                (matchesAscii(bytes, usableLength, 8, "AIFF") ||
                    matchesAscii(bytes, usableLength, 8, "AIFC")) -> parseAiff(bytes, usableLength)

            matchesAscii(bytes, usableLength, 0, "DSD ") -> parseDsf(bytes, usableLength)
            else -> null
        }
    }

    fun inferFallbackCodec(mimeType: String?, fileName: String): Pair<String, Boolean> {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val mime = mimeType.orEmpty().lowercase()
        return when {
            ext == "flac" || "flac" in mime -> "FLAC" to true
            ext == "wav" || ext == "wave" || "wav" in mime -> "WAV" to true
            ext == "aif" || ext == "aiff" || ext == "aifc" || "aiff" in mime -> "AIFF" to true
            ext == "wv" || "wavpack" in mime -> "WavPack" to true
            ext == "ape" || "ape" in mime -> "APE" to true
            ext == "tta" -> "TTA" to true
            ext == "dsf" || ext == "dff" || "dsd" in mime -> "DSD" to true
            ext == "alac" || "alac" in mime -> "ALAC" to true
            ext == "opus" || "opus" in mime -> "Opus" to false
            ext == "ogg" || ext == "oga" || "vorbis" in mime -> "Vorbis" to false
            ext == "mp3" || "mpeg" in mime -> "MP3" to false
            ext == "m4a" || ext == "aac" || "mp4" in mime || "aac" in mime -> "AAC" to false
            ext == "wma" || "wma" in mime -> "WMA" to false
            else -> ext.uppercase().ifEmpty { "AUDIO" } to false
        }
    }

    private fun parseFlac(bytes: ByteArray, length: Int): ParsedAudioHeader? {
        if (length < 26) return null
        val blockType = bytes[4].toInt() and 0x7F
        if (blockType != 0) return null // STREAMINFO must be the first metadata block.

        val b18 = bytes[18].toInt() and 0xFF
        val b19 = bytes[19].toInt() and 0xFF
        val b20 = bytes[20].toInt() and 0xFF
        val b21 = bytes[21].toInt() and 0xFF

        val sampleRate = (b18 shl 12) or (b19 shl 4) or (b20 ushr 4)
        val channels = ((b20 ushr 1) and 0x07) + 1
        val bitDepth = (((b20 and 0x01) shl 4) or (b21 ushr 4)) + 1

        if (sampleRate <= 0 || channels <= 0 || bitDepth < 4) return null
        return ParsedAudioHeader(
            codec = "FLAC",
            bitDepth = bitDepth,
            sampleRateHz = sampleRate,
            channelCount = channels,
            isLossless = true,
        )
    }

    private fun parseWav(bytes: ByteArray, length: Int): ParsedAudioHeader? {
        var offset = 12
        while (offset + 8 <= length) {
            val chunkSize = readU32LE(bytes, offset + 4)
            if (matchesAscii(bytes, length, offset, "fmt ")) {
                if (chunkSize < 16 || offset + 24 > length) return null
                val rawFormat = readU16LE(bytes, offset + 8)
                val channels = readU16LE(bytes, offset + 10)
                val sampleRate = readU32LE(bytes, offset + 12).toInt()
                val containerBits = readU16LE(bytes, offset + 22)

                var effectiveFormat = rawFormat
                var effectiveBits = containerBits
                if (rawFormat == 0xFFFE && chunkSize >= 40 && offset + 48 <= length) {
                    val validBits = readU16LE(bytes, offset + 26)
                    if (validBits in 1..64) effectiveBits = validBits
                    effectiveFormat = readU16LE(bytes, offset + 32)
                }

                if (sampleRate <= 0 || channels <= 0 || effectiveBits <= 0) return null
                val codec = if (effectiveFormat == 3) "WAV Float" else "WAV"
                return ParsedAudioHeader(
                    codec = codec,
                    bitDepth = effectiveBits,
                    sampleRateHz = sampleRate,
                    channelCount = channels,
                    isLossless = true,
                )
            }
            val paddedSize = chunkSize + (chunkSize and 1L)
            if (paddedSize < 0 || offset + 8L + paddedSize > length) return null
            offset += (8L + paddedSize).toInt()
        }
        return null
    }

    private fun parseAiff(bytes: ByteArray, length: Int): ParsedAudioHeader? {
        var offset = 12
        while (offset + 8 <= length) {
            val chunkSize = readU32BE(bytes, offset + 4)
            if (matchesAscii(bytes, length, offset, "COMM")) {
                if (chunkSize < 18 || offset + 26 > length) return null
                val channels = readU16BE(bytes, offset + 8)
                val bitDepth = readU16BE(bytes, offset + 14)
                val sampleRate = readExtended80(bytes, offset + 16)
                if (sampleRate <= 0 || channels <= 0 || bitDepth <= 0) return null
                return ParsedAudioHeader(
                    codec = "AIFF",
                    bitDepth = bitDepth,
                    sampleRateHz = sampleRate,
                    channelCount = channels,
                    isLossless = true,
                )
            }
            val paddedSize = chunkSize + (chunkSize and 1L)
            if (paddedSize < 0 || offset + 8L + paddedSize > length) return null
            offset += (8L + paddedSize).toInt()
        }
        return null
    }

    private fun parseDsf(bytes: ByteArray, length: Int): ParsedAudioHeader? {
        if (length < 64 || !matchesAscii(bytes, length, 28, "fmt ")) return null
        val channels = readU32LE(bytes, 52).toInt()
        val sampleRate = readU32LE(bytes, 56).toInt()
        val bitDepth = readU32LE(bytes, 60).toInt()
        if (sampleRate <= 0 || channels <= 0 || bitDepth <= 0) return null
        return ParsedAudioHeader(
            codec = "DSD",
            bitDepth = bitDepth,
            sampleRateHz = sampleRate,
            channelCount = channels,
            isLossless = true,
        )
    }

    private fun readExtended80(bytes: ByteArray, offset: Int): Int {
        val expAndSign = readU16BE(bytes, offset)
        val exponent = (expAndSign and 0x7FFF) - 16383
        if (exponent < 0 || exponent > 30) return 0
        var mantissa = 0L
        for (i in 0 until 8) {
            mantissa = (mantissa shl 8) or (bytes[offset + 2 + i].toLong() and 0xFFL)
        }
        if (mantissa == 0L) return 0
        val normalized = mantissa.toDouble() / 2.0.pow(63)
        val hz = (normalized * 2.0.pow(exponent)).roundToInt()
        return if ((expAndSign and 0x8000) != 0) 0 else hz
    }

    private fun matchesAscii(bytes: ByteArray, length: Int, offset: Int, tag: String): Boolean {
        if (offset < 0 || offset + tag.length > length) return false
        for (i in tag.indices) {
            if (bytes[offset + i].toInt().toChar() != tag[i]) return false
        }
        return true
    }

    private fun readU16LE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun readU32LE(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFFL) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFFL) shl 24)

    private fun readU16BE(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun readU32BE(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFFL) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 8) or
            (bytes[offset + 3].toLong() and 0xFFL)
}
