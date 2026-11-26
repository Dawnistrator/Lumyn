package com.lumyn.music

import android.content.IntentSender
import android.net.Uri
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumyn.core.audioengine.LumynPlaybackController
import com.lumyn.core.audioengine.UsbBitPerfectManager
import com.lumyn.core.database.preferences.LumynPreferencesDataSource
import com.lumyn.core.database.repository.LocalMusicRepository
import com.lumyn.core.mediascanner.lyrics.LocalLyricsLoader
import com.lumyn.core.mediascanner.scanner.AudioLibraryScanner
import com.lumyn.core.mediascanner.scanner.MediaStoreObserver
import com.lumyn.core.mediascanner.tags.TagWriteResult
import com.lumyn.core.mediascanner.tags.TrackTagEdit
import com.lumyn.core.mediascanner.tags.TrackTagWriter
import com.lumyn.core.model.AudioCapabilitiesSnapshot
import com.lumyn.core.model.AudioOutputMode
import com.lumyn.core.model.PlaybackState
import com.lumyn.core.model.PlaylistSummary
import com.lumyn.core.model.Track
import com.lumyn.core.model.UserPreferences
import com.lumyn.feature.player.LyricsUiState
import com.lumyn.feature.player.TagEditorSubmission
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class MainViewModel @Inject constructor(
    private val preferencesDataSource: LumynPreferencesDataSource,
    usbBitPerfectManager: UsbBitPerfectManager,
    private val localMusicRepository: LocalMusicRepository,
    private val scanner: AudioLibraryScanner,
    private val mediaStoreObserver: MediaStoreObserver,
    private val playbackController: LumynPlaybackController,
    private val lyricsLoader: LocalLyricsLoader,
    private val trackTagWriter: TrackTagWriter,
) : ViewModel() {

    val playbackState: StateFlow<PlaybackState> = playbackController.playbackState

    private val _lyricsState = MutableStateFlow(LyricsUiState())
    val lyricsState: StateFlow<LyricsUiState> = _lyricsState.asStateFlow()

    private val _snackbarMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val snackbarMessages: SharedFlow<String> = _snackbarMessages.asSharedFlow()

    val preferences: StateFlow<UserPreferences> = preferencesDataSource.preferences.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = UserPreferences(),
    )

    val playlists: StateFlow<List<PlaylistSummary>> = localMusicRepository.playlists.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    val capabilities: StateFlow<AudioCapabilitiesSnapshot> =
        usbBitPerfectManager.routeSnapshot
            .map { it.capabilities }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = AudioCapabilitiesSnapshot(
                    apiLevel = Build.VERSION.SDK_INT,
                    supportsFloatOutput = true,
                    supportsDirectPlayback = false,
                    supportsBitPerfectUsb = false,
                    activeOutputSummary = "Checking audio device…",
                ),
            )

    private var libraryWatchingStarted = false
    private var observerJob: Job? = null
    private var lyricsJob: Job? = null

    init {
        playbackController.connect()
        viewModelScope.launch {
            localMusicRepository.tracks.collect { tracks ->
                playbackController.syncTrackCatalog(tracks)
            }
        }
        viewModelScope.launch {
            playbackState
                .map { it.currentTrack?.id }
                .distinctUntilChanged()
                .collect { trackId ->
                    lyricsJob?.cancel()
                    if (trackId == null) {
                        _lyricsState.value = LyricsUiState()
                    } else {
                        val track = playbackState.value.currentTrack
                        if (track != null) {
                            _lyricsState.value = LyricsUiState(trackId = trackId, isLoading = true)
                            lyricsJob = viewModelScope.launch {
                                val parsed = lyricsLoader.loadLyrics(track)
                                _lyricsState.value = LyricsUiState(
                                    trackId = trackId,
                                    isLoading = false,
                                    lyrics = parsed,
                                )
                            }
                        }
                    }
                }
        }
    }

    @OptIn(FlowPreview::class)
    fun onAudioPermissionGranted() {
        if (libraryWatchingStarted) return
        libraryWatchingStarted = true

        viewModelScope.launch { scanner.triggerScan() }
        observerJob = viewModelScope.launch {
            mediaStoreObserver.changes()
                .debounce(MEDIA_STORE_DEBOUNCE_MS)
                .catch { /* Ignore observer teardown errors cleanly. */ }
                .collect { scanner.triggerScan() }
        }
    }

    fun playTracks(tracks: List<Track>, startIndex: Int) {
        playbackController.playTracks(tracks, startIndex)
    }

    fun playNext(track: Track) {
        playbackController.playNext(track)
        _snackbarMessages.tryEmit("Playing next: ${track.title}")
    }

    fun addToQueue(track: Track) {
        playbackController.addToQueue(track)
        _snackbarMessages.tryEmit("Added to queue: ${track.title}")
    }

    fun addTracksToQueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        playbackController.addTracksToQueue(tracks)
        val label = if (tracks.size == 1) "1 track" else "${tracks.size} tracks"
        _snackbarMessages.tryEmit("Added $label to queue")
    }

    fun addTrackToPlaylist(playlist: PlaylistSummary, track: Track) {
        viewModelScope.launch {
            val added = localMusicRepository.addTracksToPlaylist(playlist.id, listOf(track.id))
            _snackbarMessages.tryEmit(
                if (added > 0) "Added to \"${playlist.name}\"" else "Already in \"${playlist.name}\"",
            )
        }
    }

    fun createPlaylistWithTrack(name: String, track: Track) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            localMusicRepository.createPlaylistWithTracks(trimmed, listOf(track.id))
            _snackbarMessages.tryEmit("Added to \"$trimmed\"")
        }
    }

    fun togglePlayPause() = playbackController.togglePlayPause()
    fun skipToNext() = playbackController.skipToNext()
    fun skipToPrevious() = playbackController.skipToPrevious()
    fun seekTo(positionMs: Long) = playbackController.seekTo(positionMs)
    fun toggleShuffle() = playbackController.toggleShuffle()
    fun cycleRepeatMode() = playbackController.cycleRepeatMode()
    fun setPlaybackSpeed(speed: Float) = playbackController.setPlaybackSpeed(speed)
    fun setSleepCountdown(durationMs: Long) = playbackController.setSleepTimerCountdown(durationMs)
    fun setSleepEndOfTrack() = playbackController.setSleepTimerEndOfTrack()
    fun cancelSleepTimer() = playbackController.cancelSleepTimer()
    fun skipToQueueIndex(index: Int) = playbackController.skipToQueueIndex(index)
    fun moveQueueItem(fromIndex: Int, toIndex: Int) = playbackController.moveQueueItem(fromIndex, toIndex)
    fun removeQueueItem(index: Int) = playbackController.removeQueueItem(index)
    fun clearPlaybackError() = playbackController.clearError()

    fun setAudioOutputMode(mode: AudioOutputMode) {
        viewModelScope.launch { preferencesDataSource.setAudioOutputMode(mode) }
    }

    fun toggleFavorite(track: Track) {
        viewModelScope.launch {
            localMusicRepository.toggleFavorite(track.id, !track.isFavorite)
        }
    }

    fun saveQueueAsPlaylist(name: String) {
        val ids = playbackState.value.queue.map { it.id }
        viewModelScope.launch {
            localMusicRepository.createPlaylistWithTracks(name, ids)
            _snackbarMessages.tryEmit("Playlist \"${name.trim()}\" saved")
        }
    }

    suspend fun saveTrackTags(submission: TagEditorSubmission): IntentSender? {
        val track = submission.track
        val resolvedArtworkUri = submission.pickedArtworkUri?.let { uriStr ->
            trackTagWriter.persistPickedArtwork(track.id, Uri.parse(uriStr))
        } ?: track.artworkUri

        val edit = TrackTagEdit(
            trackId = track.id,
            contentUri = track.contentUri,
            title = submission.title,
            artist = submission.artist,
            album = submission.album,
            trackNumber = submission.trackNumber,
            year = submission.year,
            genre = submission.genre,
            artworkUri = resolvedArtworkUri,
            isCueTrack = track.isCueTrack,
            customArtworkChanged = submission.pickedArtworkUri != null,
        )
        return when (val result = trackTagWriter.saveTags(edit, writeConsentGranted = submission.writeConsentGranted)) {
            is TagWriteResult.RequiresWriteConsent -> result.intentSender
            TagWriteResult.Updated -> {
                _snackbarMessages.tryEmit("Tags written to audio file")
                null
            }
            is TagWriteResult.UpdatedLocallyOnly -> {
                _snackbarMessages.tryEmit(result.reason)
                null
            }
        }
    }

    override fun onCleared() {
        observerJob?.cancel()
        lyricsJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val MEDIA_STORE_DEBOUNCE_MS = 1_500L
    }
}
