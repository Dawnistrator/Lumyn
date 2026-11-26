package com.lumyn.core.model

/** A single local audio file (or clipped CUE track) indexed into the library. */
data class Track(
    val id: Long,
    val contentUri: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val albumId: Long,
    val durationMs: Long,
    val trackNumber: Int,
    val discNumber: Int,
    val year: Int,
    val genre: String,
    val folderPath: String,
    val fileName: String,
    val mimeType: String,
    val audioSpec: AudioSpec,
    val artworkUri: String?,
    val isFavorite: Boolean,
    val dateAddedSeconds: Long,
    val replayGainTrackDb: Float? = null,
    val replayGainAlbumDb: Float? = null,
    val replayGainTrackPeak: Float? = null,
    val replayGainAlbumPeak: Float? = null,
    val clipStartMs: Long? = null,
    val clipEndMs: Long? = null,
    val cueSourcePath: String? = null,
    val playCount: Int = 0,
    val lastPlayedAtMs: Long = 0L,
    val totalPlayDurationMs: Long = 0L,
) {
    val isCueTrack: Boolean
        get() = clipStartMs != null
}
