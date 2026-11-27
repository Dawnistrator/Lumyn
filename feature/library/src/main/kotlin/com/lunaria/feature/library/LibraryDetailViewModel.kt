package com.lunaria.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lunaria.core.database.repository.LocalMusicRepository
import com.lunaria.core.model.M3uPlaylistParser
import com.lunaria.core.model.SmartPlaylistEngine
import com.lunaria.core.model.SmartPlaylistType
import com.lunaria.core.model.Track
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class LibraryCollectionKind {
    ALBUM,
    ARTIST,
    FOLDER,
    PLAYLIST,
    SMART,
    ;

    companion object {
        fun fromRouteValue(raw: String): LibraryCollectionKind =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: ALBUM
    }
}

data class LibraryDetailUiState(
    val kind: LibraryCollectionKind = LibraryCollectionKind.ALBUM,
    val key: String = "",
    val title: String = "Collection",
    val subtitle: String = "",
    val artworkUri: String? = null,
    val tracks: List<Track> = emptyList(),
    val totalDurationMs: Long = 0L,
    val hasHiRes: Boolean = false,
    val isUserPlaylist: Boolean = false,
    val playlistId: Long? = null,
    val notice: String? = null,
    val deleted: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryDetailViewModel @Inject constructor(
    private val musicRepository: LocalMusicRepository,
) : ViewModel() {

    private data class DetailTarget(
        val kind: LibraryCollectionKind,
        val key: String,
    )

    private val targetFlow = MutableStateFlow<DetailTarget?>(null)
    private val noticeFlow = MutableStateFlow<String?>(null)
    private val deletedFlow = MutableStateFlow(false)

    private val scopedPlaylistTracksFlow = targetFlow.flatMapLatest { target ->
        val playlistId = if (target?.kind == LibraryCollectionKind.PLAYLIST) target.key.toLongOrNull() else null
        if (playlistId != null) {
            musicRepository.observePlaylistTracks(playlistId)
        } else {
            flowOf(emptyList())
        }
    }

    private val catalogFlow = combine(
        targetFlow,
        musicRepository.tracks,
        musicRepository.playlists,
        scopedPlaylistTracksFlow,
    ) { target, allTracks, playlists, playlistTracks ->
        DetailCatalog(target, allTracks, playlists, playlistTracks)
    }

    val uiState: StateFlow<LibraryDetailUiState> = combine(
        catalogFlow,
        noticeFlow,
        deletedFlow,
    ) { catalog, notice, deleted ->
        val target = catalog.target ?: return@combine LibraryDetailUiState()
        val allTracks = catalog.allTracks

        val resolved = when (target.kind) {
            LibraryCollectionKind.ALBUM -> {
                val albumId = target.key.toLongOrNull()
                val items = allTracks
                    .filter { albumId != null && it.albumId == albumId }
                    .sortedWith(compareBy({ it.discNumber }, { it.trackNumber }, { it.title.lowercase() }))
                val first = items.firstOrNull()
                LibraryDetailUiState(
                    kind = target.kind,
                    key = target.key,
                    title = first?.album ?: "Album",
                    subtitle = first?.let { it.albumArtist.ifBlank { it.artist } } ?: "No tracks",
                    artworkUri = items.firstNotNullOfOrNull { it.artworkUri },
                    tracks = items,
                    totalDurationMs = items.sumOf { it.durationMs },
                    hasHiRes = items.any { it.audioSpec.isHiRes },
                )
            }

            LibraryCollectionKind.ARTIST -> {
                val artistName = target.key
                val items = allTracks
                    .filter {
                        it.albumArtist.ifBlank { it.artist }.trim().ifEmpty { "Unknown Artist" }
                            .equals(artistName, ignoreCase = true)
                    }
                    .sortedWith(compareBy({ it.album.lowercase() }, { it.discNumber }, { it.trackNumber }))
                val albumCount = items.map { it.albumId }.distinct().size
                LibraryDetailUiState(
                    kind = target.kind,
                    key = target.key,
                    title = artistName,
                    subtitle = "$albumCount ${if (albumCount == 1) "album" else "albums"}",
                    artworkUri = items.firstNotNullOfOrNull { it.artworkUri },
                    tracks = items,
                    totalDurationMs = items.sumOf { it.durationMs },
                    hasHiRes = items.any { it.audioSpec.isHiRes },
                )
            }

            LibraryCollectionKind.FOLDER -> {
                val folderPath = target.key
                val items = allTracks
                    .filter { (it.folderPath.ifBlank { "Root" }).equals(folderPath, ignoreCase = true) }
                    .sortedWith(compareBy({ it.trackNumber }, { it.fileName.lowercase() }))
                val displayName = folderPath.substringAfterLast('/').ifBlank { folderPath }
                LibraryDetailUiState(
                    kind = target.kind,
                    key = target.key,
                    title = displayName,
                    subtitle = folderPath,
                    artworkUri = items.firstNotNullOfOrNull { it.artworkUri },
                    tracks = items,
                    totalDurationMs = items.sumOf { it.durationMs },
                    hasHiRes = items.any { it.audioSpec.isHiRes },
                )
            }

            LibraryCollectionKind.PLAYLIST -> {
                val playlistId = target.key.toLongOrNull()
                val summary = catalog.playlists.firstOrNull { it.id == playlistId }
                val items = catalog.playlistTracks
                LibraryDetailUiState(
                    kind = target.kind,
                    key = target.key,
                    title = summary?.name ?: "Playlist",
                    subtitle = "Local playlist",
                    artworkUri = items.firstNotNullOfOrNull { it.artworkUri },
                    tracks = items,
                    totalDurationMs = items.sumOf { it.durationMs },
                    hasHiRes = items.any { it.audioSpec.isHiRes },
                    isUserPlaylist = true,
                    playlistId = playlistId,
                )
            }

            LibraryCollectionKind.SMART -> {
                val smartType = SmartPlaylistType.entries
                    .firstOrNull { it.name.equals(target.key, ignoreCase = true) }
                    ?: SmartPlaylistType.RECENTLY_PLAYED
                val items = SmartPlaylistEngine.tracksForSmartPlaylist(smartType, allTracks)
                LibraryDetailUiState(
                    kind = target.kind,
                    key = target.key,
                    title = smartType.title,
                    subtitle = smartType.subtitle,
                    artworkUri = items.firstNotNullOfOrNull { it.artworkUri },
                    tracks = items,
                    totalDurationMs = items.sumOf { it.durationMs },
                    hasHiRes = items.any { it.audioSpec.isHiRes },
                )
            }
        }
        resolved.copy(notice = notice, deleted = deleted)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LibraryDetailUiState(),
    )

    fun load(kindRaw: String, key: String) {
        val parsedKind = LibraryCollectionKind.fromRouteValue(kindRaw)
        val incoming = DetailTarget(parsedKind, key)
        if (targetFlow.value != incoming) {
            noticeFlow.value = null
            deletedFlow.value = false
            targetFlow.value = incoming
        }
    }

    fun toggleFavorite(track: Track) {
        viewModelScope.launch { musicRepository.toggleFavorite(track.id, !track.isFavorite) }
    }

    fun removeFromPlaylist(trackId: Long) {
        val playlistId = uiState.value.playlistId ?: return
        viewModelScope.launch {
            musicRepository.removeTrackFromPlaylist(playlistId, trackId)
            noticeFlow.value = "Removed track from playlist."
        }
    }

    fun renamePlaylist(newName: String) {
        val playlistId = uiState.value.playlistId ?: return
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            musicRepository.renamePlaylist(playlistId, trimmed)
            noticeFlow.value = "Renamed playlist to \"$trimmed\"."
        }
    }

    fun deletePlaylist() {
        val playlistId = uiState.value.playlistId ?: return
        viewModelScope.launch {
            musicRepository.deletePlaylist(playlistId)
            deletedFlow.value = true
        }
    }

    suspend fun exportPlaylistM3u8(): String {
        val playlistId = uiState.value.playlistId ?: return ""
        val tracks = musicRepository.getPlaylistTracks(playlistId)
        return M3uPlaylistParser.export(tracks)
    }

    fun notifyExported() {
        noticeFlow.value = "Exported \"${uiState.value.title}\" to .m3u8"
    }

    fun clearNotice() {
        noticeFlow.value = null
    }

    private data class DetailCatalog(
        val target: DetailTarget?,
        val allTracks: List<Track>,
        val playlists: List<com.lunaria.core.model.PlaylistSummary>,
        val playlistTracks: List<Track>,
    )
}
