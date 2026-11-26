package com.lumyn.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CueAndM3uParserTest {

    @Test
    fun parsesCueSheetTimestampsWith75FpsConversionAndChainedEndTimes() {
        val cue = """
            PERFORMER "Pink Floyd"
            TITLE "Wish You Were Here"
            FILE "Wish You Were Here.flac" WAVE
              TRACK 01 AUDIO
                TITLE "Shine On You Crazy Diamond"
                INDEX 01 00:00:00
              TRACK 02 AUDIO
                TITLE "Welcome to the Machine"
                INDEX 01 13:32:15
        """.trimIndent()

        val parsed = CueSheetParser.parse(cue)
        assertNotNull(parsed)
        assertEquals("Wish You Were Here", parsed!!.albumTitle)
        assertEquals("Pink Floyd", parsed.albumPerformer)
        assertEquals("Wish You Were Here.flac", parsed.fileName)
        assertEquals(2, parsed.tracks.size)

        val expectedSecondStart = (13L * 60L + 32L) * 1_000L + (15L * 1_000L) / 75L
        assertEquals(0L, parsed.tracks[0].startMs)
        assertEquals(expectedSecondStart, parsed.tracks[0].endMs)
        assertEquals(expectedSecondStart, parsed.tracks[1].startMs)
        assertNull(parsed.tracks[1].endMs)
    }

    @Test
    fun exportsAndRoundTripsM3u8AgainstLocalLibrary() {
        val t1 = Track(
            id = 1L,
            contentUri = "content://media/external/audio/media/1",
            title = "Blue in Green",
            artist = "Miles Davis",
            album = "Kind of Blue",
            albumArtist = "Miles Davis",
            albumId = 10L,
            durationMs = 337_000L,
            trackNumber = 3,
            discNumber = 1,
            year = 1959,
            genre = "Jazz",
            folderPath = "Music/Jazz",
            fileName = "03_Blue_in_Green.flac",
            mimeType = "audio/flac",
            audioSpec = AudioSpec("FLAC", 24, 192_000, 4608, 2, true),
            artworkUri = null,
            isFavorite = false,
            dateAddedSeconds = 100L,
        )
        val exported = M3uPlaylistParser.export(listOf(t1))
        val parsedEntries = M3uPlaylistParser.parse(exported)
        assertEquals(1, parsedEntries.size)
        assertEquals("Music/Jazz/03_Blue_in_Green.flac", parsedEntries[0].pathOrUri)
        assertEquals(337, parsedEntries[0].durationSeconds)

        val matched = M3uPlaylistParser.matchTracks(parsedEntries, listOf(t1))
        assertEquals(listOf(t1), matched)
    }
}
