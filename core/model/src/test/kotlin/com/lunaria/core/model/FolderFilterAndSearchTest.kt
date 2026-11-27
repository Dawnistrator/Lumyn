package com.lunaria.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderFilterAndSearchTest {

    private fun sampleTrack(
        codec: String = "FLAC",
        bitDepth: Int = 24,
        sampleRateHz: Int = 96_000,
        lossless: Boolean = true,
        favorite: Boolean = false,
        title: String = "Midnight Signal",
        artist: String = "Aiko",
        album: String = "Glass Waves",
        folderPath: String = "Music/HiRes",
    ) = Track(
        id = 1L,
        contentUri = "content://media/external/audio/media/1",
        title = title,
        artist = artist,
        album = album,
        albumArtist = artist,
        albumId = 10L,
        durationMs = 240_000L,
        trackNumber = 1,
        discNumber = 1,
        year = 2024,
        genre = "Electronic",
        folderPath = folderPath,
        fileName = "01-midnight.flac",
        mimeType = "audio/flac",
        audioSpec = AudioSpec(codec, bitDepth, sampleRateHz, 2800, 2, lossless),
        artworkUri = null,
        isFavorite = favorite,
        dateAddedSeconds = 1_700_000_000L,
    )

    @Test
    fun defaultBlacklistRejectsRingtonesAndMessagingAudio() {
        val config = FolderFilterConfig()
        assertTrue(config.isFolderAllowed("Music/FLAC"))
        assertTrue(config.isFolderAllowed("/Music/Japanese/"))
        assertFalse(config.isFolderAllowed("Notifications"))
        assertFalse(config.isFolderAllowed("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Audio/Sent"))
        assertFalse(config.isFolderAllowed("Ringtones\\Custom"))
    }

    @Test
    fun blacklistPathRuleMatchesExactAndNestedSubfolders() {
        val config = FolderFilterConfig(
            mode = FolderFilterMode.BLACKLIST,
            excludedFolders = setOf("Music/Podcasts"),
        )
        assertFalse(config.isFolderAllowed("music/podcasts"))
        assertFalse(config.isFolderAllowed("Music/Podcasts/2026"))
        assertTrue(config.isFolderAllowed("Music/PodcastsArchive"))
    }

    @Test
    fun whitelistRejectsWhenEmptyAndMatchesExactOrSubfolder() {
        val empty = FolderFilterConfig(mode = FolderFilterMode.WHITELIST, includedFolders = emptySet())
        assertFalse(empty.isFolderAllowed("Music/FLAC"))

        val configured = FolderFilterConfig(
            mode = FolderFilterMode.WHITELIST,
            includedFolders = setOf("Music/HiRes"),
        )
        assertTrue(configured.isFolderAllowed("Music/HiRes"))
        assertTrue(configured.isFolderAllowed("music/hires/DSD"))
        assertFalse(configured.isFolderAllowed("Music/Other"))
        assertFalse(configured.isFolderAllowed("Music/HiResBackup"))
    }

    @Test
    fun searchCriteriaFiltersByChipsAndText() {
        val hiResFlacFav = sampleTrack(favorite = true)
        val cdWav = sampleTrack(codec = "WAV", bitDepth = 16, sampleRateHz = 44_100, favorite = false)
        val lossyMp3 = sampleTrack(codec = "MP3", bitDepth = 16, sampleRateHz = 44_100, lossless = false)

        assertTrue(hiResFlacFav.matches(SearchCriteria(filters = setOf(SearchFilterChip.HI_RES, SearchFilterChip.FLAC, SearchFilterChip.FAVORITES))))
        assertFalse(cdWav.matches(SearchCriteria(filters = setOf(SearchFilterChip.HI_RES))))
        assertTrue(cdWav.matches(SearchCriteria(filters = setOf(SearchFilterChip.LOSSLESS))))
        assertFalse(lossyMp3.matches(SearchCriteria(filters = setOf(SearchFilterChip.LOSSLESS))))
        assertTrue(hiResFlacFav.matches(SearchCriteria(query = "glass")))
        assertTrue(hiResFlacFav.matches(SearchCriteria(query = "hires")))
        assertFalse(hiResFlacFav.matches(SearchCriteria(query = "nonexistent")))
    }
}
