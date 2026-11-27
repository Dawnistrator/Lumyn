package com.lunaria.core.audioengine

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.lunaria.core.model.AudioSpec
import com.lunaria.core.model.Track

private const val EXTRA_CODEC = "lunaria.codec"
private const val EXTRA_BIT_DEPTH = "lunaria.bit_depth"
private const val EXTRA_SAMPLE_RATE = "lunaria.sample_rate"
private const val EXTRA_BITRATE_KBPS = "lunaria.bitrate_kbps"
private const val EXTRA_CHANNELS = "lunaria.channels"
private const val EXTRA_IS_LOSSLESS = "lunaria.is_lossless"
private const val EXTRA_DURATION_MS = "lunaria.duration_ms"
private const val EXTRA_CLIP_START_MS = "lunaria.clip_start_ms"
private const val EXTRA_CLIP_END_MS = "lunaria.clip_end_ms"
private const val EXTRA_RG_TRACK_DB = "lunaria.rg_track_db"
private const val EXTRA_RG_ALBUM_DB = "lunaria.rg_album_db"
private const val EXTRA_RG_TRACK_PEAK = "lunaria.rg_track_peak"
private const val EXTRA_RG_ALBUM_PEAK = "lunaria.rg_album_peak"

fun Track.toMediaItem(): MediaItem {
    val extras = Bundle().apply {
        putString(EXTRA_CODEC, audioSpec.codec)
        putInt(EXTRA_BIT_DEPTH, audioSpec.bitDepth)
        putInt(EXTRA_SAMPLE_RATE, audioSpec.sampleRateHz)
        putInt(EXTRA_BITRATE_KBPS, audioSpec.bitrateKbps)
        putInt(EXTRA_CHANNELS, audioSpec.channelCount)
        putBoolean(EXTRA_IS_LOSSLESS, audioSpec.isLossless)
        putLong(EXTRA_DURATION_MS, durationMs)
        clipStartMs?.let { putLong(EXTRA_CLIP_START_MS, it) }
        clipEndMs?.let { putLong(EXTRA_CLIP_END_MS, it) }
        replayGainTrackDb?.let { putFloat(EXTRA_RG_TRACK_DB, it) }
        replayGainAlbumDb?.let { putFloat(EXTRA_RG_ALBUM_DB, it) }
        replayGainTrackPeak?.let { putFloat(EXTRA_RG_TRACK_PEAK, it) }
        replayGainAlbumPeak?.let { putFloat(EXTRA_RG_ALBUM_PEAK, it) }
    }

    val metadata = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .setAlbumTitle(album)
        .setAlbumArtist(albumArtist)
        .setTrackNumber(trackNumber.takeIf { it > 0 })
        .setDiscNumber(discNumber.takeIf { it > 0 })
        .setGenre(genre.takeIf { it.isNotBlank() })
        .setArtworkUri(artworkUri?.let(Uri::parse))
        .setIsPlayable(true)
        .setIsBrowsable(false)
        .setExtras(extras)
        .build()

    val builder = MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(Uri.parse(contentUri))
        .setMediaMetadata(metadata)

    val startMs = clipStartMs
    if (startMs != null) {
        val clipBuilder = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(startMs.coerceAtLeast(0L))
        val endMs = clipEndMs
        if (endMs != null && endMs > startMs) {
            clipBuilder.setEndPositionMs(endMs)
        }
        builder.setClippingConfiguration(clipBuilder.build())
    }

    return builder.build()
}

fun MediaItem.toFallbackTrack(): Track {
    val id = mediaId.toLongOrNull() ?: 0L
    val meta = mediaMetadata
    val extras = meta.extras
    val spec = if (extras != null && extras.containsKey(EXTRA_CODEC)) {
        AudioSpec(
            codec = extras.getString(EXTRA_CODEC, "AUDIO"),
            bitDepth = extras.getInt(EXTRA_BIT_DEPTH, 16),
            sampleRateHz = extras.getInt(EXTRA_SAMPLE_RATE, 44_100),
            bitrateKbps = extras.getInt(EXTRA_BITRATE_KBPS, 0),
            channelCount = extras.getInt(EXTRA_CHANNELS, 2),
            isLossless = extras.getBoolean(EXTRA_IS_LOSSLESS, false),
        )
    } else {
        AudioSpec("AUDIO", 16, 44_100, 0, 2, false)
    }
    val clipStart = extras?.takeIf { it.containsKey(EXTRA_CLIP_START_MS) }?.getLong(EXTRA_CLIP_START_MS)
    val clipEnd = extras?.takeIf { it.containsKey(EXTRA_CLIP_END_MS) }?.getLong(EXTRA_CLIP_END_MS)
    return Track(
        id = id,
        contentUri = localConfiguration?.uri?.toString().orEmpty(),
        title = meta.title?.toString().orEmpty().ifBlank { "Unknown Track" },
        artist = meta.artist?.toString().orEmpty().ifBlank { "Unknown Artist" },
        album = meta.albumTitle?.toString().orEmpty().ifBlank { "Unknown Album" },
        albumArtist = meta.albumArtist?.toString().orEmpty(),
        albumId = 0L,
        durationMs = extras?.getLong(EXTRA_DURATION_MS, 0L) ?: 0L,
        trackNumber = meta.trackNumber ?: 0,
        discNumber = meta.discNumber ?: 1,
        year = 0,
        genre = meta.genre?.toString().orEmpty(),
        folderPath = "",
        fileName = "",
        mimeType = localConfiguration?.mimeType.orEmpty(),
        audioSpec = spec,
        artworkUri = meta.artworkUri?.toString(),
        isFavorite = false,
        dateAddedSeconds = 0L,
        replayGainTrackDb = extras?.takeIf { it.containsKey(EXTRA_RG_TRACK_DB) }?.getFloat(EXTRA_RG_TRACK_DB),
        replayGainAlbumDb = extras?.takeIf { it.containsKey(EXTRA_RG_ALBUM_DB) }?.getFloat(EXTRA_RG_ALBUM_DB),
        replayGainTrackPeak = extras?.takeIf { it.containsKey(EXTRA_RG_TRACK_PEAK) }?.getFloat(EXTRA_RG_TRACK_PEAK),
        replayGainAlbumPeak = extras?.takeIf { it.containsKey(EXTRA_RG_ALBUM_PEAK) }?.getFloat(EXTRA_RG_ALBUM_PEAK),
        clipStartMs = clipStart,
        clipEndMs = clipEnd,
    )
}
