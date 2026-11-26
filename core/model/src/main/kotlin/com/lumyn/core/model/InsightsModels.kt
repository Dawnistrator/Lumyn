package com.lumyn.core.model

enum class SmartPlaylistType(
    val title: String,
    val subtitle: String,
) {
    RECENTLY_PLAYED(
        title = "Recently Played",
        subtitle = "Tracks you spun most recently",
    ),
    MOST_PLAYED(
        title = "Most Played",
        subtitle = "Your top local favorites by play count",
    ),
    FORGOTTEN_GEMS(
        title = "Forgotten Gems",
        subtitle = "Never played or untouched for 30+ days",
    ),
    HI_RES_COLLECTION(
        title = "Hi-Res Collection",
        subtitle = "24-bit+ or >48 kHz lossless masters",
    ),
    FAVORITES(
        title = "Favorites",
        subtitle = "Hearted tracks in your library",
    ),
}

data class SmartPlaylistSummary(
    val type: SmartPlaylistType,
    val title: String,
    val subtitle: String,
    val trackCount: Int,
)

data class ArtistPlayStat(
    val artistName: String,
    val playCount: Int,
    val totalListeningMs: Long,
    val artworkUri: String?,
)

data class ListeningStats(
    val totalPlays: Int = 0,
    val totalListeningMs: Long = 0L,
    val uniqueTracksPlayed: Int = 0,
    val hiResPlays: Int = 0,
    val topArtists: List<ArtistPlayStat> = emptyList(),
    val topTracks: List<Track> = emptyList(),
)

/**
 * Pure-Kotlin generator for 100% offline smart playlists and local listening analytics.
 */
object SmartPlaylistEngine {

    fun recentlyPlayed(tracks: List<Track>, limit: Int = DEFAULT_LIMIT): List<Track> =
        tracks
            .filter { it.lastPlayedAtMs > 0L }
            .sortedByDescending { it.lastPlayedAtMs }
            .take(limit)

    fun mostPlayed(tracks: List<Track>, limit: Int = DEFAULT_LIMIT): List<Track> =
        tracks
            .filter { it.playCount > 0 }
            .sortedWith(compareByDescending<Track> { it.playCount }.thenByDescending { it.lastPlayedAtMs })
            .take(limit)

    fun forgottenGems(
        tracks: List<Track>,
        nowEpochMs: Long,
        staleThresholdDays: Int = DEFAULT_STALE_DAYS,
        limit: Int = DEFAULT_LIMIT,
    ): List<Track> {
        val cutoffMs = nowEpochMs - staleThresholdDays.toLong() * MILLIS_PER_DAY
        return tracks
            .filter { it.playCount == 0 || it.lastPlayedAtMs < cutoffMs }
            .sortedWith(
                compareByDescending<Track> { it.audioSpec.isHiRes }
                    .thenBy { it.playCount }
                    .thenBy { it.dateAddedSeconds },
            )
            .take(limit)
    }

    fun hiResCollection(tracks: List<Track>): List<Track> =
        tracks
            .filter { it.audioSpec.isHiRes }
            .sortedWith(
                compareByDescending<Track> { it.audioSpec.sampleRateHz }
                    .thenByDescending { it.audioSpec.bitDepth }
                    .thenBy { it.title.lowercase() },
            )

    fun favorites(tracks: List<Track>): List<Track> =
        tracks
            .filter { it.isFavorite }
            .sortedBy { it.title.lowercase() }

    fun tracksForSmartPlaylist(
        type: SmartPlaylistType,
        tracks: List<Track>,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): List<Track> = when (type) {
        SmartPlaylistType.RECENTLY_PLAYED -> recentlyPlayed(tracks)
        SmartPlaylistType.MOST_PLAYED -> mostPlayed(tracks)
        SmartPlaylistType.FORGOTTEN_GEMS -> forgottenGems(tracks, nowEpochMs = nowEpochMs)
        SmartPlaylistType.HI_RES_COLLECTION -> hiResCollection(tracks)
        SmartPlaylistType.FAVORITES -> favorites(tracks)
    }

    fun summarizeSmartPlaylists(
        tracks: List<Track>,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): List<SmartPlaylistSummary> = SmartPlaylistType.entries.map { type ->
        val matching = tracksForSmartPlaylist(type, tracks, nowEpochMs)
        SmartPlaylistSummary(
            type = type,
            title = type.title,
            subtitle = type.subtitle,
            trackCount = matching.size,
        )
    }

    fun computeStats(tracks: List<Track>, topLimit: Int = 10): ListeningStats {
        val playedTracks = tracks.filter { it.playCount > 0 }
        if (playedTracks.isEmpty()) return ListeningStats()

        val totalPlays = playedTracks.sumOf { it.playCount }
        val totalListeningMs = playedTracks.sumOf { track ->
            if (track.totalPlayDurationMs > 0L) track.totalPlayDurationMs else track.playCount * track.durationMs
        }
        val hiResPlays = playedTracks.filter { it.audioSpec.isHiRes }.sumOf { it.playCount }

        val topArtists = playedTracks
            .groupBy { it.albumArtist.ifBlank { it.artist }.ifBlank { "Unknown Artist" } }
            .map { (artistName, items) ->
                val plays = items.sumOf { it.playCount }
                val listeningMs = items.sumOf { item ->
                    if (item.totalPlayDurationMs > 0L) item.totalPlayDurationMs else item.playCount * item.durationMs
                }
                ArtistPlayStat(
                    artistName = artistName,
                    playCount = plays,
                    totalListeningMs = listeningMs,
                    artworkUri = items.firstNotNullOfOrNull { it.artworkUri },
                )
            }
            .sortedWith(compareByDescending<ArtistPlayStat> { it.playCount }.thenByDescending { it.totalListeningMs })
            .take(topLimit)

        val topTracks = mostPlayed(playedTracks, limit = topLimit)

        return ListeningStats(
            totalPlays = totalPlays,
            totalListeningMs = totalListeningMs,
            uniqueTracksPlayed = playedTracks.size,
            hiResPlays = hiResPlays,
            topArtists = topArtists,
            topTracks = topTracks,
        )
    }

    private const val DEFAULT_LIMIT = 50
    private const val DEFAULT_STALE_DAYS = 30
    private const val MILLIS_PER_DAY = 86_400_000L
}
