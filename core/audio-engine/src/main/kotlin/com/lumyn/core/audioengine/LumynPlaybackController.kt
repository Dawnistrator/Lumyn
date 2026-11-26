package com.lumyn.core.audioengine

import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.lumyn.core.database.preferences.LumynPreferencesDataSource
import com.lumyn.core.model.AudioOutputMode
import com.lumyn.core.model.EqualizerSettings
import com.lumyn.core.model.PlaybackState
import com.lumyn.core.model.RepeatMode
import com.lumyn.core.model.SleepTimerState
import com.lumyn.core.model.Track
import com.lumyn.core.model.clampPlaybackSpeed
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Application-scoped bridge between Jetpack Media3's [MediaController] and Compose UI state.
 * Also merges live [AudioSignalPathTracker] telemetry so Now Playing shows honest DSP/Direct/USB state.
 */
@Singleton
class LumynPlaybackController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesDataSource: LumynPreferencesDataSource,
    private val usbBitPerfectManager: UsbBitPerfectManager,
    private val signalPathTracker: AudioSignalPathTracker,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val trackCatalog = HashMap<Long, Track>()
    private val pendingActions = mutableListOf<(MediaController) -> Unit>()

    private var currentMode: AudioOutputMode = AudioOutputMode.DSP_FLOAT_32
    private var currentEqualizer: EqualizerSettings = EqualizerSettings()
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var positionJob: Job? = null
    private var sleepTimerJob: Job? = null

    init {
        scope.launch {
            combine(
                preferencesDataSource.preferences,
                usbBitPerfectManager.routeSnapshot,
                signalPathTracker.signalPath,
            ) { prefs, route, livePath ->
                currentMode = prefs.audioOutputMode
                currentEqualizer = prefs.equalizer
                val currentTrack = _playbackState.value.currentTrack
                livePath ?: AudioSignalPathBuilder.build(
                    track = currentTrack,
                    mode = prefs.audioOutputMode,
                    telemetry = RuntimeAudioTelemetry(),
                    capabilities = route.capabilities,
                    bitPerfectStatus = route.bitPerfectStatus,
                    equalizerSettings = prefs.equalizer,
                )
            }.collect { path ->
                _playbackState.value = _playbackState.value.copy(signalPath = path)
            }
        }
        scope.launch {
            signalPathTracker.notices.collect { notice ->
                _playbackState.value = _playbackState.value.copy(errorMessage = notice)
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePositionTicker(isPlaying)
            syncStateFromController()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED &&
                _playbackState.value.sleepTimer is SleepTimerState.EndOfTrack
            ) {
                cancelSleepTimer()
            }
            syncStateFromController()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
                _playbackState.value.sleepTimer is SleepTimerState.EndOfTrack
            ) {
                controller?.pause()
                cancelSleepTimer()
            }
            syncStateFromController()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) = syncStateFromController()
        override fun onRepeatModeChanged(repeatMode: Int) = syncStateFromController()
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = syncStateFromController()
        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) =
            syncStateFromController()

        override fun onPlayerError(error: PlaybackException) {
            _playbackState.value = _playbackState.value.copy(
                isPlaying = false,
                errorMessage = "Unable to play track (${error.errorCodeName})",
            )
        }
    }

    fun connect() {
        usbBitPerfectManager.startMonitoring()
        scope.launch { ensureController() }
    }

    fun syncTrackCatalog(tracks: List<Track>) {
        scope.launch {
            trackCatalog.clear()
            tracks.forEach { trackCatalog[it.id] = it }
            syncStateFromController()
        }
    }

    fun playTracks(tracks: List<Track>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        val safeIndex = startIndex.coerceIn(0, tracks.lastIndex)
        tracks.forEach { trackCatalog[it.id] = it }
        withController { player ->
            val items = tracks.map { it.toMediaItem() }
            player.setMediaItems(items, safeIndex, C.TIME_UNSET)
            player.prepare()
            player.play()
            syncStateFromController(clearError = true)
        }
    }

    fun togglePlayPause() {
        withController { player ->
            if (player.isPlaying) {
                player.pause()
            } else {
                if (player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0) {
                    player.prepare()
                }
                player.play()
            }
        }
    }

    fun skipToNext() {
        withController { player ->
            if (player.hasNextMediaItem()) {
                player.seekToNext()
                syncStateFromController(clearError = true)
            }
        }
    }

    fun skipToPrevious() {
        withController { player ->
            player.seekToPrevious()
            syncStateFromController(clearError = true)
        }
    }

    fun seekTo(positionMs: Long) {
        withController { player ->
            val target = positionMs.coerceAtLeast(0L)
            player.seekTo(target)
            _playbackState.value = _playbackState.value.copy(positionMs = target)
        }
    }

    fun toggleShuffle() {
        withController { player ->
            player.shuffleModeEnabled = !player.shuffleModeEnabled
        }
    }

    fun cycleRepeatMode() {
        withController { player ->
            val nextMode = fromMedia3RepeatMode(player.repeatMode).next()
            player.repeatMode = toMedia3RepeatMode(nextMode)
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        val clamped = clampPlaybackSpeed(speed)
        withController { player ->
            player.setPlaybackSpeed(clamped)
            syncStateFromController()
        }
    }

    fun playNext(track: Track) {
        trackCatalog[track.id] = track
        withController { player ->
            if (player.mediaItemCount == 0) {
                playTracks(listOf(track), 0)
            } else {
                val insertAt = QueueOperations.playNextInsertionIndex(
                    currentIndex = player.currentMediaItemIndex,
                    queueSize = player.mediaItemCount,
                )
                player.addMediaItem(insertAt, track.toMediaItem())
                syncStateFromController()
            }
        }
    }

    fun addToQueue(track: Track) {
        trackCatalog[track.id] = track
        withController { player ->
            if (player.mediaItemCount == 0) {
                playTracks(listOf(track), 0)
            } else {
                player.addMediaItem(track.toMediaItem())
                syncStateFromController()
            }
        }
    }

    fun addTracksToQueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        tracks.forEach { trackCatalog[it.id] = it }
        withController { player ->
            if (player.mediaItemCount == 0) {
                playTracks(tracks, 0)
            } else {
                player.addMediaItems(tracks.map { it.toMediaItem() })
                syncStateFromController()
            }
        }
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        withController { player ->
            if (QueueOperations.isValidMove(fromIndex, toIndex, player.mediaItemCount)) {
                player.moveMediaItem(fromIndex, toIndex)
                syncStateFromController()
            }
        }
    }

    fun removeQueueItem(index: Int) {
        withController { player ->
            if (QueueOperations.isValidRemove(index, player.mediaItemCount)) {
                player.removeMediaItem(index)
                syncStateFromController()
            }
        }
    }

    fun skipToQueueIndex(index: Int) {
        withController { player ->
            if (index in 0 until player.mediaItemCount) {
                player.seekToDefaultPosition(index)
                player.play()
                syncStateFromController(clearError = true)
            }
        }
    }

    fun setSleepTimerCountdown(durationMs: Long) {
        if (durationMs <= 0L) {
            cancelSleepTimer()
            return
        }
        sleepTimerJob?.cancel()
        val endTimeMs = SystemClock.elapsedRealtime() + durationMs
        _playbackState.value = _playbackState.value.copy(sleepTimer = SleepTimerState.Countdown(durationMs))
        sleepTimerJob = scope.launch {
            while (isActive) {
                val remaining = (endTimeMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                if (remaining <= 0L) {
                    controller?.pause()
                    _playbackState.value = _playbackState.value.copy(sleepTimer = SleepTimerState.Off)
                    break
                }
                _playbackState.value = _playbackState.value.copy(
                    sleepTimer = SleepTimerState.Countdown(remaining),
                )
                delay(1_000L)
            }
        }
    }

    fun setSleepTimerEndOfTrack() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _playbackState.value = _playbackState.value.copy(sleepTimer = SleepTimerState.EndOfTrack)
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _playbackState.value = _playbackState.value.copy(sleepTimer = SleepTimerState.Off)
    }

    fun clearError() {
        _playbackState.value = _playbackState.value.copy(errorMessage = null)
    }

    private fun withController(action: (MediaController) -> Unit) {
        scope.launch {
            val active = controller
            if (active != null) {
                action(active)
            } else {
                pendingActions += action
                ensureController()
            }
        }
    }

    private fun ensureController() {
        if (controller != null || controllerFuture != null) return
        val sessionToken = SessionToken(context, ComponentName(context, LumynPlaybackService::class.java))
        val future = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                try {
                    val connected = future.get()
                    controller = connected
                    connected.addListener(playerListener)
                    updatePositionTicker(connected.isPlaying)
                    syncStateFromController()
                    val queued = pendingActions.toList()
                    pendingActions.clear()
                    queued.forEach { it(connected) }
                } catch (_: Throwable) {
                    controllerFuture = null
                    pendingActions.clear()
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    private fun updatePositionTicker(isPlaying: Boolean) {
        positionJob?.cancel()
        positionJob = null
        if (!isPlaying) return
        positionJob = scope.launch {
            while (isActive) {
                val player = controller ?: break
                _playbackState.value = _playbackState.value.copy(
                    positionMs = player.currentPosition.coerceAtLeast(0L),
                    durationMs = resolveDurationMs(player),
                )
                delay(POSITION_POLL_INTERVAL_MS)
            }
        }
    }

    private fun syncStateFromController(clearError: Boolean = false) {
        val player = controller ?: return
        val queue = buildList(player.mediaItemCount) {
            for (i in 0 until player.mediaItemCount) {
                val item = player.getMediaItemAt(i)
                val trackId = item.mediaId.toLongOrNull()
                add((trackId?.let { trackCatalog[it] }) ?: item.toFallbackTrack())
            }
        }
        val currentIndex = player.currentMediaItemIndex.takeIf { it in queue.indices } ?: -1
        val currentTrack = queue.getOrNull(currentIndex)
        signalPathTracker.updateTrackAndMode(currentTrack, currentMode)

        val route = usbBitPerfectManager.routeSnapshot.value
        val computedPath = signalPathTracker.signalPath.value ?: AudioSignalPathBuilder.build(
            track = currentTrack,
            mode = currentMode,
            telemetry = RuntimeAudioTelemetry(),
            capabilities = route.capabilities,
            bitPerfectStatus = route.bitPerfectStatus,
            equalizerSettings = currentEqualizer,
        )

        _playbackState.value = _playbackState.value.copy(
            currentTrack = currentTrack,
            queue = queue,
            currentIndex = currentIndex,
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = resolveDurationMs(player, currentTrack),
            repeatMode = fromMedia3RepeatMode(player.repeatMode),
            shuffleEnabled = player.shuffleModeEnabled,
            speed = player.playbackParameters.speed,
            errorMessage = if (clearError) null else _playbackState.value.errorMessage,
            signalPath = computedPath,
        )
    }

    private fun resolveDurationMs(
        player: Player,
        track: Track? = _playbackState.value.currentTrack,
    ): Long {
        val playerDuration = player.duration
        return if (playerDuration != C.TIME_UNSET && playerDuration > 0L) {
            playerDuration
        } else {
            track?.durationMs ?: 0L
        }
    }

    private fun fromMedia3RepeatMode(mode: Int): RepeatMode = when (mode) {
        Player.REPEAT_MODE_ALL -> RepeatMode.ALL
        Player.REPEAT_MODE_ONE -> RepeatMode.ONE
        else -> RepeatMode.OFF
    }

    private fun toMedia3RepeatMode(mode: RepeatMode): Int = when (mode) {
        RepeatMode.OFF -> Player.REPEAT_MODE_OFF
        RepeatMode.ALL -> Player.REPEAT_MODE_ALL
        RepeatMode.ONE -> Player.REPEAT_MODE_ONE
    }

    private companion object {
        const val POSITION_POLL_INTERVAL_MS = 250L
    }
}
