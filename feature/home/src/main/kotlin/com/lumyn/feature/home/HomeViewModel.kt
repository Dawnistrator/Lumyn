package com.lumyn.feature.home

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumyn.core.audioengine.UsbBitPerfectManager
import com.lumyn.core.database.repository.LocalMusicRepository
import com.lumyn.core.mediascanner.scanner.AudioLibraryScanner
import com.lumyn.core.mediascanner.scanner.ScanStatus
import com.lumyn.core.model.AlbumSummary
import com.lumyn.core.model.AudioCapabilitiesSnapshot
import com.lumyn.core.model.ListeningStats
import com.lumyn.core.model.SmartPlaylistSummary
import com.lumyn.core.model.Track
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val recentTracks: List<Track> = emptyList(),
    val spotlightAlbums: List<AlbumSummary> = emptyList(),
    val smartPlaylists: List<SmartPlaylistSummary> = emptyList(),
    val listeningStats: ListeningStats = ListeningStats(),
    val totalTrackCount: Int = 0,
    val hiResTrackCount: Int = 0,
    val scanStatus: ScanStatus = ScanStatus.Idle,
    val capabilities: AudioCapabilitiesSnapshot = AudioCapabilitiesSnapshot(
        apiLevel = Build.VERSION.SDK_INT,
        supportsFloatOutput = true,
        supportsDirectPlayback = false,
        supportsBitPerfectUsb = false,
        activeOutputSummary = "Checking audio device…",
    ),
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val musicRepository: LocalMusicRepository,
    private val scanner: AudioLibraryScanner,
    usbBitPerfectManager: UsbBitPerfectManager,
) : ViewModel() {

    init {
        usbBitPerfectManager.startMonitoring()
    }

    private val catalogFlow = combine(
        musicRepository.tracks,
        musicRepository.recentTracks,
        musicRepository.albums,
        musicRepository.smartPlaylists,
        musicRepository.listeningStats,
    ) { allTracks, recent, albums, smarts, stats ->
        HomeCatalogSnapshot(allTracks, recent, albums, smarts, stats)
    }

    val uiState: StateFlow<HomeUiState> = combine(
        catalogFlow,
        scanner.scanState,
        usbBitPerfectManager.routeSnapshot,
    ) { catalog, scanStatus, route ->
        HomeUiState(
            recentTracks = catalog.recentTracks,
            spotlightAlbums = catalog.albums.sortedByDescending { it.hasHiRes }.take(8),
            smartPlaylists = catalog.smartPlaylists,
            listeningStats = catalog.listeningStats,
            totalTrackCount = catalog.allTracks.size,
            hiResTrackCount = catalog.allTracks.count { it.audioSpec.isHiRes },
            scanStatus = scanStatus,
            capabilities = route.capabilities,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(),
    )

    fun triggerScan() {
        viewModelScope.launch { scanner.triggerScan() }
    }

    fun toggleFavorite(track: Track) {
        viewModelScope.launch { musicRepository.toggleFavorite(track.id, !track.isFavorite) }
    }

    private data class HomeCatalogSnapshot(
        val allTracks: List<Track>,
        val recentTracks: List<Track>,
        val albums: List<AlbumSummary>,
        val smartPlaylists: List<SmartPlaylistSummary>,
        val listeningStats: ListeningStats,
    )
}
