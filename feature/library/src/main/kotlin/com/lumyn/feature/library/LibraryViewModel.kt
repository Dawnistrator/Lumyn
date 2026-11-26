package com.lumyn.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumyn.core.database.repository.LocalMusicRepository
import com.lumyn.core.mediascanner.scanner.AudioLibraryScanner
import com.lumyn.core.mediascanner.scanner.ScanStatus
import com.lumyn.core.model.AlbumSummary
import com.lumyn.core.model.ArtistSummary
import com.lumyn.core.model.CueSheetParser
import com.lumyn.core.model.CueVirtualTracks
import com.lumyn.core.model.FolderSummary
import com.lumyn.core.model.M3uPlaylistParser
import com.lumyn.core.model.PlaylistSummary
import com.lumyn.core.model.SmartPlaylistSummary
import com.lumyn.core.model.Track
import com.lumyn.core.model.TrackSortOrder
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LibraryUiState(
    val tracks: List<Track> = emptyList(),
    val albums: List<AlbumSummary> = emptyList(),
    val artists: List<ArtistSummary> = emptyList(),
    val folders: List<FolderSummary> = emptyList(),
    val playlists: List<PlaylistSummary> = emptyList(),
    val smartPlaylists: List<SmartPlaylistSummary> = emptyList(),
    val sortOrder: TrackSortOrder = TrackSortOrder.TITLE_ASC,
    val scanStatus: ScanStatus = ScanStatus.Idle,
    val playlistNotice: String? = null,
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val musicRepository: LocalMusicRepository,
    private val scanner: AudioLibraryScanner,
) : ViewModel() {

    private val sortOrder = MutableStateFlow(TrackSortOrder.TITLE_ASC)
    private val playlistNotice = MutableStateFlow<String?>(null)

    private val sortedTracks = combine(musicRepository.tracks, sortOrder) { tracks, order ->
        when (order) {
            TrackSortOrder.TITLE_ASC -> tracks.sortedBy { it.title.lowercase() }
            TrackSortOrder.ARTIST_ASC -> tracks.sortedWith(
                compareBy({ it.artist.lowercase() }, { it.album.lowercase() }, { it.trackNumber }),
            )
            TrackSortOrder.ALBUM_ASC -> tracks.sortedWith(
                compareBy({ it.album.lowercase() }, { it.discNumber }, { it.trackNumber }),
            )
            TrackSortOrder.DATE_ADDED_DESC -> tracks.sortedByDescending { it.dateAddedSeconds }
            TrackSortOrder.DURATION_DESC -> tracks.sortedByDescending { it.durationMs }
        }
    }.flowOn(Dispatchers.Default)

    private val primaryCatalogFlow = combine(
        sortedTracks,
        musicRepository.albums,
        musicRepository.artists,
        musicRepository.folders,
        musicRepository.playlists,
    ) { tracks, albums, artists, folders, playlists ->
        PrimaryCatalog(tracks, albums, artists, folders, playlists)
    }

    val uiState: StateFlow<LibraryUiState> = combine(
        primaryCatalogFlow,
        musicRepository.smartPlaylists,
        sortOrder,
        scanner.scanState,
        playlistNotice,
    ) { catalog, smarts, order, scan, notice ->
        LibraryUiState(
            tracks = catalog.tracks,
            albums = catalog.albums,
            artists = catalog.artists,
            folders = catalog.folders,
            playlists = catalog.playlists,
            smartPlaylists = smarts,
            sortOrder = order,
            scanStatus = scan,
            playlistNotice = notice,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LibraryUiState(),
    )

    fun setSortOrder(order: TrackSortOrder) {
        sortOrder.value = order
    }

    fun triggerScan() {
        viewModelScope.launch { scanner.triggerScan() }
    }

    fun toggleFavorite(track: Track) {
        viewModelScope.launch { musicRepository.toggleFavorite(track.id, !track.isFavorite) }
    }

    fun createPlaylist(name: String) {
        viewModelScope.launch { musicRepository.createPlaylist(name) }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch { musicRepository.deletePlaylist(playlistId) }
    }

    suspend fun getPlaylistTracks(playlistId: Long): List<Track> =
        musicRepository.getPlaylistTracks(playlistId)

    suspend fun exportPlaylistM3u8(playlistId: Long): String {
        val tracks = musicRepository.getPlaylistTracks(playlistId)
        return M3uPlaylistParser.export(tracks)
    }

    fun importM3uPlaylist(rawContent: String, fileName: String) {
        viewModelScope.launch {
            val entries = M3uPlaylistParser.parse(rawContent)
            if (entries.isEmpty()) {
                playlistNotice.value = "No playlist entries found in \"$fileName\"."
                return@launch
            }
            val library = musicRepository.tracks.first()
            val matched = M3uPlaylistParser.matchTracks(entries, library)
            val playlistName = fileName.substringBeforeLast('.').trim().ifBlank { "Imported Playlist" }
            musicRepository.createPlaylistWithTracks(playlistName, matched.map { it.id })
            playlistNotice.value = if (matched.isNotEmpty()) {
                "Imported \"$playlistName\" (${matched.size} ${if (matched.size == 1) "track" else "tracks"})."
            } else {
                "Created \"$playlistName\", but 0 local tracks matched file paths."
            }
        }
    }

    fun importCueSheet(rawContent: String, fileName: String) {
        viewModelScope.launch {
            val cue = CueSheetParser.parse(rawContent)
            if (cue == null || cue.tracks.isEmpty()) {
                playlistNotice.value = "Could not parse CUE sheet tracks from \"$fileName\"."
                return@launch
            }
            val library = musicRepository.tracks.first().filter { !it.isCueTrack }
            val targetName = cue.fileName.replace('\\', '/').substringAfterLast('/')
            val targetStem = targetName.substringBeforeLast('.')
            val cueStem = fileName.substringBeforeLast('.')
            val matchedTrack = library.firstOrNull { track ->
                targetName.isNotEmpty() && track.fileName.equals(targetName, ignoreCase = true)
            } ?: library.firstOrNull { track ->
                targetStem.isNotEmpty() && track.fileName.substringBeforeLast('.').equals(targetStem, ignoreCase = true)
            } ?: library.firstOrNull { track ->
                cueStem.isNotEmpty() && track.fileName.substringBeforeLast('.').equals(cueStem, ignoreCase = true)
            } ?: library.firstOrNull { track ->
                track.album.equals(cue.albumTitle, ignoreCase = true)
            }

            val playlistName = cue.albumTitle.ifBlank { fileName.substringBeforeLast('.') }
            if (matchedTrack == null) {
                musicRepository.createPlaylist(playlistName)
                playlistNotice.value =
                    "Created \"$playlistName\", but no matching local audio file was found for CUE playback."
                return@launch
            }

            val virtualTracks = CueVirtualTracks.buildVirtualTracks(
                cue = cue,
                backingTrack = matchedTrack,
                cueSourceKey = fileName,
            )
            musicRepository.upsertVirtualCueTracks(virtualTracks)
            musicRepository.createPlaylistWithTracks(playlistName, virtualTracks.map { it.id })
            playlistNotice.value =
                "Imported CUE \"$playlistName\" (${virtualTracks.size} virtual tracks from ${matchedTrack.fileName})."
        }
    }

    fun notifyExportSuccess(playlistName: String) {
        playlistNotice.value = "Exported \"$playlistName\" to .m3u8"
    }

    fun clearPlaylistNotice() {
        playlistNotice.value = null
    }

    private data class PrimaryCatalog(
        val tracks: List<Track>,
        val albums: List<AlbumSummary>,
        val artists: List<ArtistSummary>,
        val folders: List<FolderSummary>,
        val playlists: List<PlaylistSummary>,
    )
}
