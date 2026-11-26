package com.lumyn.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartPlaylistEngineTest {

    private fun track(
        id: Long,
        artist: String = "Artist",
        isHiRes: Boolean = false,
        isFavorite: Boolean = false,
        playCount: Int = 0,
        lastPlayedAtMs: Long = 0L,
        totalPlayDurationMs: Long = 0L,
    ) = Track(
        id = id,
        contentUri = "content://media/$id",
        title = "Track $id",
        artist = artist,
        album = "Album",
        albumArtist = artist,
        albumId = 1L,
        durationMs = 180_000L,
        trackNumber = id.toInt(),
        discNumber = 1,
        year = 2024,
        genre = "Jazz",
        folderPath = "Music",
        fileName = "t$id.flac",
        mimeType = "audio/flac",
        audioSpec = AudioSpec(
            codec = if (isHiRes) "FLAC" else "MP3",
            bitDepth = if (isHiRes) 24 else 16,
            sampleRateHz = if (isHiRes) 96_000 else 44_100,
            bitrateKbps = if (isHiRes) 3000 else 320,
            channelCount = 2,
            isLossless = isHiRes,
        ),
        artworkUri = null,
        isFavorite = isFavorite,
        dateAddedSeconds = id * 10L,
        playCount = playCount,
        lastPlayedAtMs = lastPlayedAtMs,
        totalPlayDurationMs = totalPlayDurationMs,
    )

    @Test
    fun generatesSmartPlaylistsAndStatsAccurately() {
        val now = 100L * 86_400_000L
        val tracks = listOf(
            track(1, artist = "Alice", isHiRes = true, isFavorite = true, playCount = 8, lastPlayedAtMs = now - 1_000L, totalPlayDurationMs = 900_000L),
            track(2, artist = "Alice", isHiRes = false, playCount = 3, lastPlayedAtMs = now - 5_000L, totalPlayDurationMs = 300_000L),
            track(3, artist = "Bob", isHiRes = true, playCount = 0, lastPlayedAtMs = 0L),
            track(4, artist = "Carol", isHiRes = false, playCount = 2, lastPlayedAtMs = now - 45L * 86_400_000L),
        )

        val recent = SmartPlaylistEngine.recentlyPlayed(tracks)
        assertEquals(listOf(1L, 2L, 4L), recent.map { it.id })

        val most = SmartPlaylistEngine.mostPlayed(tracks)
        assertEquals(listOf(1L, 2L, 4L), most.map { it.id })

        val forgotten = SmartPlaylistEngine.forgottenGems(tracks, nowEpochMs = now, staleThresholdDays = 30)
        // Track 3 is Hi-Res with 0 plays -> comes first, then Track 4 (last played 45 days ago)
        assertEquals(listOf(3L, 4L), forgotten.map { it.id })

        val hiRes = SmartPlaylistEngine.hiResCollection(tracks)
        assertEquals(listOf(1L, 3L), hiRes.map { it.id })

        val stats = SmartPlaylistEngine.computeStats(tracks)
        assertEquals(13, stats.totalPlays)
        assertEquals(3, stats.uniqueTracksPlayed)
        assertEquals(8, stats.hiResPlays)
        assertEquals("Alice", stats.topArtists.first().artistName)
        assertEquals(11, stats.topArtists.first().playCount)
        assertTrue(stats.totalListeningMs >= 1_200_000L)
    }
}
