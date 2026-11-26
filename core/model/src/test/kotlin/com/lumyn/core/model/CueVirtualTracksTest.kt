package com.lumyn.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CueVirtualTracksTest {

    @Test
    fun generatesNegativeNonCollidingClippedVirtualTracks() {
        val cueRaw = """
            PERFORMER "Symphony Orchestra"
            TITLE "Live Concert"
            FILE "concert.flac" WAVE
              TRACK 01 AUDIO
                TITLE "Overture"
                INDEX 01 00:00:00
              TRACK 02 AUDIO
                TITLE "Adagio"
                PERFORMER "Guest Soloist"
                INDEX 01 04:10:00
        """.trimIndent()
        val parsed = CueSheetParser.parse(cueRaw)!!
        val backing = Track(
            id = 42L,
            contentUri = "content://media/external/audio/media/42",
            title = "concert",
            artist = "Unknown Artist",
            album = "Unknown Album",
            albumArtist = "Unknown Artist",
            albumId = 7L,
            durationMs = 600_000L,
            trackNumber = 1,
            discNumber = 1,
            year = 2023,
            genre = "Classical",
            folderPath = "Music/Concerts",
            fileName = "concert.flac",
            mimeType = "audio/flac",
            audioSpec = AudioSpec("FLAC", 24, 96_000, 3200, 2, true),
            artworkUri = null,
            isFavorite = false,
            dateAddedSeconds = 1_000L,
        )

        val virtuals = CueVirtualTracks.buildVirtualTracks(parsed, backing, "concert.cue")
        assertEquals(2, virtuals.size)
        assertTrue(virtuals.all { it.id < 0L && it.isCueTrack })
        assertTrue(virtuals[0].id != virtuals[1].id)

        assertEquals(0L, virtuals[0].clipStartMs)
        assertEquals(250_000L, virtuals[0].clipEndMs)
        assertEquals(250_000L, virtuals[0].durationMs)

        assertEquals(250_000L, virtuals[1].clipStartMs)
        assertNull(virtuals[1].clipEndMs)
        assertEquals(350_000L, virtuals[1].durationMs)
        assertEquals("Guest Soloist", virtuals[1].artist)
        assertEquals("Live Concert", virtuals[1].album)
    }
}
