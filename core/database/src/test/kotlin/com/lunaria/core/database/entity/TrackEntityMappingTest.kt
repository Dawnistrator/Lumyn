package com.lunaria.core.database.entity

import com.lunaria.core.model.AudioSpec
import com.lunaria.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackEntityMappingTest {

    @Test
    fun `track entity round trip preserves cue clip bounds replaygain and play history`() {
        val track = Track(
            id = -42L,
            contentUri = "content://media/external/audio/media/99",
            title = "Cue Movement I",
            artist = "Ensemble Test",
            album = "Live Master",
            albumArtist = "Ensemble Test",
            albumId = 77L,
            durationMs = 245_000L,
            trackNumber = 3,
            discNumber = 1,
            year = 2024,
            genre = "Classical",
            folderPath = "/Music/HiRes",
            fileName = "live_master.flac",
            mimeType = "audio/flac",
            audioSpec = AudioSpec(
                codec = "FLAC",
                bitDepth = 24,
                sampleRateHz = 96_000,
                bitrateKbps = 2850,
                channelCount = 2,
                isLossless = true,
            ),
            artworkUri = "file:///data/user/0/com.lunaria.music/files/custom_art/cover.jpg",
            isFavorite = true,
            dateAddedSeconds = 1_732_000_000L,
            replayGainTrackDb = -4.2f,
            replayGainAlbumDb = -3.8f,
            replayGainTrackPeak = 0.96f,
            replayGainAlbumPeak = 0.98f,
            clipStartMs = 120_000L,
            clipEndMs = 365_000L,
            cueSourcePath = "/Music/HiRes/live_master.cue",
            playCount = 12,
            lastPlayedAtMs = 1_732_100_000_000L,
            totalPlayDurationMs = 2_400_000L,
        )

        val roundTripped = track.toEntity().toModel()
        assertEquals(track, roundTripped)
        assertTrue(roundTripped.isCueTrack)
        assertTrue(roundTripped.audioSpec.isHiRes)
    }

    @Test
    fun `standard mp3 track preserves non-cue and lossy flags`() {
        val track = Track(
            id = 15L,
            contentUri = "content://media/external/audio/media/15",
            title = "Night Drive",
            artist = "Synthwave",
            album = "City",
            albumArtist = "",
            albumId = 5L,
            durationMs = 198_000L,
            trackNumber = 1,
            discNumber = 1,
            year = 2020,
            genre = "Electronic",
            folderPath = "/Music/MP3",
            fileName = "night.mp3",
            mimeType = "audio/mpeg",
            audioSpec = AudioSpec(
                codec = "MP3",
                bitDepth = 16,
                sampleRateHz = 44_100,
                bitrateKbps = 320,
                channelCount = 2,
                isLossless = false,
            ),
            artworkUri = null,
            isFavorite = false,
            dateAddedSeconds = 1_700_000_000L,
        )

        val roundTripped = track.toEntity().toModel()
        assertEquals(track, roundTripped)
        assertFalse(roundTripped.isCueTrack)
        assertFalse(roundTripped.audioSpec.isHiRes)
    }
}
