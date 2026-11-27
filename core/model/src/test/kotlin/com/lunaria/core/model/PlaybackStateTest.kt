package com.lunaria.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStateTest {

    private fun dummyTrack(id: Long) = Track(
        id = id,
        contentUri = "content://media/external/audio/media/$id",
        title = "Track $id",
        artist = "Artist",
        album = "Album",
        albumArtist = "Artist",
        albumId = 1L,
        durationMs = 180_000L,
        trackNumber = id.toInt(),
        discNumber = 1,
        year = 2025,
        genre = "Jazz",
        folderPath = "Music",
        fileName = "track_$id.flac",
        mimeType = "audio/flac",
        audioSpec = AudioSpec("FLAC", 24, 96_000, 2400, 2, true),
        artworkUri = null,
        isFavorite = false,
        dateAddedSeconds = 100L,
    )

    @Test
    fun repeatModeCyclesOffAllOne() {
        assertEquals(RepeatMode.ALL, RepeatMode.OFF.next())
        assertEquals(RepeatMode.ONE, RepeatMode.ALL.next())
        assertEquals(RepeatMode.OFF, RepeatMode.ONE.next())
    }

    @Test
    fun clampPlaybackSpeedStaysWithinSupportedRange() {
        assertEquals(0.5f, clampPlaybackSpeed(0.1f), 0f)
        assertEquals(1.25f, clampPlaybackSpeed(1.25f), 0f)
        assertEquals(2.0f, clampPlaybackSpeed(3.0f), 0f)
    }

    @Test
    fun hasNextAndPreviousReflectQueueAndRepeatMode() {
        val tracks = listOf(dummyTrack(1L), dummyTrack(2L))
        val atEnd = PlaybackState(queue = tracks, currentIndex = 1, repeatMode = RepeatMode.OFF)
        assertFalse(atEnd.hasNext)
        assertTrue(atEnd.hasPrevious)

        val atEndRepeatAll = atEnd.copy(repeatMode = RepeatMode.ALL)
        assertTrue(atEndRepeatAll.hasNext)
    }
}
