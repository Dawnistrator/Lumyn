package com.lumyn.core.mediascanner.metadata

import android.content.Context
import android.media.AudioFormat
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import com.lumyn.core.model.AudioSpec
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.FileInputStream
import javax.inject.Inject
import javax.inject.Singleton

data class InspectedAudioMetadata(
    val audioSpec: AudioSpec,
    val replayGain: ReplayGainTags = ReplayGainTags(),
)

/**
 * Combines fast binary header & ReplayGain inspection (FLAC/WAV/AIFF/DSF, ID3v2 TXXX, Ogg/Opus, MP4)
 * with a bounded MediaExtractor fallback for compressed/ALAC containers across API 26+.
 */
@Singleton
class MediaExtractorAudioInspector @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun inspect(
        uri: Uri,
        mimeType: String?,
        fileName: String,
        mediaStoreBitrateBps: Int,
        fileSizeBytes: Long,
        durationMs: Long,
    ): AudioSpec = inspectWithTags(
        uri = uri,
        mimeType = mimeType,
        fileName = fileName,
        mediaStoreBitrateBps = mediaStoreBitrateBps,
        fileSizeBytes = fileSizeBytes,
        durationMs = durationMs,
    ).audioSpec

    fun inspectWithTags(
        uri: Uri,
        mimeType: String?,
        fileName: String,
        mediaStoreBitrateBps: Int,
        fileSizeBytes: Long,
        durationMs: Long,
    ): InspectedAudioMetadata {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val lowerMime = mimeType.orEmpty().lowercase()
        val headerBytes = readHeaderPrefixSafely(uri, ext, lowerMime)
        val header = if (headerBytes != null && shouldInspectBinaryHeader(ext, lowerMime)) {
            AudioHeaderInspector.inspectHeader(headerBytes, headerBytes.size)
        } else {
            null
        }
        val replayGain = inspectReplayGain(uri, ext, lowerMime, headerBytes)
        val estimatedBitrateKbps = computeBitrateKbps(mediaStoreBitrateBps, fileSizeBytes, durationMs)

        if (header != null) {
            return InspectedAudioMetadata(
                audioSpec = AudioSpec(
                    codec = header.codec,
                    bitDepth = header.bitDepth,
                    sampleRateHz = header.sampleRateHz,
                    bitrateKbps = estimatedBitrateKbps,
                    channelCount = header.channelCount,
                    isLossless = header.isLossless,
                ),
                replayGain = replayGain,
            )
        }

        val (fallbackCodec, fallbackLossless) = AudioHeaderInspector.inferFallbackCodec(mimeType, fileName)
        if (isFastLossyContainer(ext, lowerMime, estimatedBitrateKbps)) {
            val defaultRate = if (ext == "opus" || lowerMime.contains("opus")) 48_000 else 44_100
            return InspectedAudioMetadata(
                audioSpec = AudioSpec(
                    codec = fallbackCodec,
                    bitDepth = 16,
                    sampleRateHz = defaultRate,
                    bitrateKbps = estimatedBitrateKbps.coerceAtLeast(0),
                    channelCount = 2,
                    isLossless = false,
                ),
                replayGain = replayGain,
            )
        }

        var sampleRate = 44_100
        var channels = 2
        var bitDepth = 16
        var bitrateKbps = estimatedBitrateKbps
        var resolvedCodec = fallbackCodec
        var resolvedLossless = fallbackLossless

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val trackMime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (!trackMime.startsWith("audio/")) continue

                if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                }
                if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
                if (bitrateKbps <= 0 && format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                    bitrateKbps = format.getInteger(MediaFormat.KEY_BIT_RATE) / 1_000
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    format.containsKey("bits-per-sample")
                ) {
                    bitDepth = format.getInteger("bits-per-sample").coerceAtLeast(8)
                } else if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    bitDepth = pcmEncodingToBits(format.getInteger(MediaFormat.KEY_PCM_ENCODING))
                }
                if (trackMime.contains("alac", ignoreCase = true)) {
                    resolvedCodec = "ALAC"
                    resolvedLossless = true
                }
                break
            }
        } catch (_: Throwable) {
            // Fall back to container-inferred defaults on devices missing an optional extractor.
        } finally {
            runCatching { extractor.release() }
        }

        return InspectedAudioMetadata(
            audioSpec = AudioSpec(
                codec = resolvedCodec,
                bitDepth = bitDepth,
                sampleRateHz = sampleRate,
                bitrateKbps = bitrateKbps.coerceAtLeast(0),
                channelCount = channels.coerceAtLeast(1),
                isLossless = resolvedLossless,
            ),
            replayGain = replayGain,
        )
    }

    private fun inspectReplayGain(
        uri: Uri,
        ext: String,
        lowerMime: String,
        headerBytes: ByteArray?,
    ): ReplayGainTags {
        val fromHeader = headerBytes?.let { ReplayGainTagParser.parseBytes(it, it.size) }
        if (fromHeader != null && hasAnyGainTag(fromHeader)) {
            return fromHeader
        }
        val isFlac = ext == "flac" || lowerMime.contains("flac") ||
            hasHeaderPrefix(headerBytes, "fLaC")
        val isId3Mp3 = ext == "mp3" || lowerMime.contains("mpeg") ||
            hasHeaderPrefix(headerBytes, "ID3")
        val isMp4 = ext in MP4_EXTENSIONS || lowerMime.contains("mp4") || lowerMime.contains("alac")
        if (isFlac || isId3Mp3 || isMp4) {
            val fromSeekable = runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).use { fis ->
                        when {
                            isFlac -> ReplayGainTagParser.parseSeekableFlac(fis.channel)
                            isId3Mp3 -> ReplayGainTagParser.parseSeekableId3v2(fis.channel)
                            else -> ReplayGainTagParser.parseSeekableMp4(fis.channel)
                        }
                    }
                }
            }.getOrNull()
            if (fromSeekable != null && hasAnyGainTag(fromSeekable)) {
                return fromSeekable
            }
        }
        return fromHeader ?: ReplayGainTags()
    }

    private fun hasHeaderPrefix(headerBytes: ByteArray?, magic: String): Boolean {
        if (headerBytes == null || headerBytes.size < magic.length) return false
        for (i in magic.indices) {
            if (headerBytes[i].toInt().toChar() != magic[i]) return false
        }
        return true
    }

    private fun readHeaderPrefixSafely(uri: Uri, ext: String, lowerMime: String): ByteArray? = try {
        val maxRead = if (shouldInspectBinaryHeader(ext, lowerMime) || ext in TAGGED_AUDIO_EXTENSIONS) {
            TAG_PREFIX_BYTES
        } else {
            FAST_PREFIX_BYTES
        }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val buf = ByteArray(maxRead)
            var total = 0
            while (total < buf.size) {
                val read = stream.read(buf, total, buf.size - total)
                if (read <= 0) break
                total += read
            }
            if (total > 0) buf.copyOf(total) else null
        }
    } catch (_: Throwable) {
        null
    }

    private fun hasAnyGainTag(tags: ReplayGainTags): Boolean =
        tags.trackGainDb != null || tags.albumGainDb != null ||
            tags.trackPeak != null || tags.albumPeak != null

    private fun shouldInspectBinaryHeader(ext: String, mime: String): Boolean =
        ext in LOSSLESS_HEADER_EXTENSIONS ||
            mime.contains("flac") ||
            mime.contains("wav") ||
            mime.contains("aiff") ||
            mime.contains("dsd")

    private fun isFastLossyContainer(ext: String, lowerMime: String, estimatedBitrateKbps: Int): Boolean {
        if (ext in FAST_LOSSY_EXTENSIONS ||
            lowerMime == "audio/mpeg" ||
            lowerMime == "audio/ogg" ||
            lowerMime == "audio/opus" ||
            lowerMime == "audio/aac"
        ) {
            return true
        }
        if ((ext == "m4a" || ext == "mp4" || lowerMime == "audio/mp4") &&
            estimatedBitrateKbps in 1 until ALAC_PROBE_THRESHOLD_KBPS
        ) {
            return true
        }
        return false
    }

    private fun computeBitrateKbps(mediaStoreBitrateBps: Int, fileSizeBytes: Long, durationMs: Long): Int {
        if (mediaStoreBitrateBps > 0) return mediaStoreBitrateBps / 1_000
        if (fileSizeBytes > 0L && durationMs > 0L) {
            return ((fileSizeBytes * 8L) / durationMs).toInt().coerceAtLeast(0)
        }
        return 0
    }

    private fun pcmEncodingToBits(encoding: Int): Int = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> 8
        AudioFormat.ENCODING_PCM_FLOAT -> 32
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
        AudioFormat.ENCODING_PCM_32BIT -> 32
        else -> 16
    }

    private companion object {
        val LOSSLESS_HEADER_EXTENSIONS = setOf("flac", "wav", "wave", "aif", "aiff", "dsf", "dff")
        val TAGGED_AUDIO_EXTENSIONS = setOf("flac", "mp3", "ogg", "oga", "opus")
        val MP4_EXTENSIONS = setOf("m4a", "mp4", "alac", "aac")
        val FAST_LOSSY_EXTENSIONS = setOf("mp3", "ogg", "oga", "opus", "aac")
        const val ALAC_PROBE_THRESHOLD_KBPS = 380
        const val TAG_PREFIX_BYTES = 64 * 1024
        const val FAST_PREFIX_BYTES = 16 * 1024
    }
}
