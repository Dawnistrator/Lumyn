package com.lunaria.core.model

data class AlbumSummary(
    val id: Long,
    val title: String,
    val artist: String,
    val year: Int,
    val trackCount: Int,
    val artworkUri: String?,
    val hasHiRes: Boolean,
)

data class ArtistSummary(
    val name: String,
    val albumCount: Int,
    val trackCount: Int,
    val artworkUri: String?,
)

data class FolderSummary(
    val path: String,
    val displayName: String,
    val trackCount: Int,
    val totalDurationMs: Long,
)

data class PlaylistSummary(
    val id: Long,
    val name: String,
    val trackCount: Int,
    val createdAtEpochSeconds: Long,
)

enum class TrackSortOrder {
    TITLE_ASC,
    ARTIST_ASC,
    ALBUM_ASC,
    DATE_ADDED_DESC,
    DURATION_DESC,
}
