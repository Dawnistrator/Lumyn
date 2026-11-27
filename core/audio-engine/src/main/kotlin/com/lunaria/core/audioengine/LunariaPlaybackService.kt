package com.lunaria.core.audioengine

import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.lunaria.core.database.preferences.LunariaPreferencesDataSource
import com.lunaria.core.database.repository.LocalMusicRepository
import com.lunaria.core.model.AudioOutputMode
import com.lunaria.core.model.EqualizerSettings
import com.lunaria.core.model.PersistedQueueState
import com.lunaria.core.model.RepeatMode
import com.lunaria.core.model.Track
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground playback service. Owns ExoPlayer, hot-swaps the audio sink between
 * DSP 32-bit Float (with live RBJ biquad EQ/ReplayGain/crossfade) and Purist Bit-Perfect mode
 * without dropping MediaSession, persists queue across restarts, records offline play history,
 * exposes Android Auto browse trees + playback resumption, and updates Home widgets.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class LunariaPlaybackService : MediaLibraryService() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ServiceDeps {
        fun preferencesDataSource(): LunariaPreferencesDataSource
        fun usbBitPerfectManager(): UsbBitPerfectManager
        fun signalPathTracker(): AudioSignalPathTracker
        fun dspConfigStore(): LunariaDspConfigStore
        fun localMusicRepository(): LocalMusicRepository
        fun widgetNotifier(): PlaybackWidgetNotifier
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ioPersistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var deps: ServiceDeps
    private var mediaSession: MediaLibrarySession? = null
    private var currentMode: AudioOutputMode = AudioOutputMode.DSP_FLOAT_32
    private var currentEqualizer: EqualizerSettings = EqualizerSettings()
    private var currentTrack: Track? = null

    private val playHistoryTracker = PlayHistoryTracker()
    private var monitorJob: Job? = null
    private var fadeInStartElapsedMs: Long = 0L
    private var fadeInDurationMs: Long = 0L
    private var pendingAutoCrossfadeFadeIn: Boolean = false
    private var queueRestoreComplete: Boolean = false
    private var isApplyingRestoredQueue: Boolean = false
    private var lastQueuePersistElapsedMs: Long = 0L

    private val analyticsListener = object : AnalyticsListener {
        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            deps.signalPathTracker().updateDecoder(decoderName)
        }

        override fun onAudioTrackInitialized(
            eventTime: AnalyticsListener.EventTime,
            audioTrackConfig: AudioSink.AudioTrackConfig,
        ) {
            deps.signalPathTracker().updateAudioTrack(
                encoding = audioTrackConfig.encoding,
                sampleRateHz = audioTrackConfig.sampleRate,
                offload = audioTrackConfig.offload,
            )
        }
    }

    private val sessionPlayerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val player = mediaSession?.player
            if (player != null && player.mediaItemCount > 0 && !isApplyingRestoredQueue) {
                queueRestoreComplete = true
            }
            val wasAuto = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
            if (wasAuto && pendingAutoCrossfadeFadeIn && isCrossfadeEligible(player)) {
                val halfFade = (currentEqualizer.crossfadeDurationSec * 1_000L) / 2L
                fadeInDurationMs = halfFade
                fadeInStartElapsedMs = SystemClock.elapsedRealtime()
                player?.volume = 0f
            } else {
                resetCrossfadeFadeState(player)
            }
            pendingAutoCrossfadeFadeIn = false

            serviceScope.launch {
                val track = resolveTrackAsync(mediaItem)
                currentTrack = track
                val resolvedDuration = track?.durationMs
                    ?: player?.duration?.takeIf { it != C.TIME_UNSET && it > 0L }
                    ?: 0L
                playHistoryTracker.onTrackChanged(track?.id, resolvedDuration)
                syncDspState()
                deps.usbBitPerfectManager().applyMixerForTrack(currentMode, track)
                deps.signalPathTracker().updateTrackAndMode(track, currentMode)
                deps.widgetNotifier().onPlaybackStateChanged(
                    track = track,
                    isPlaying = mediaSession?.player?.isPlaying == true,
                )
                persistCurrentQueue()
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            val player = mediaSession?.player
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                resetCrossfadeFadeState(player)
            } else if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION &&
                oldPosition.mediaItemIndex == newPosition.mediaItemIndex
            ) {
                // Repeat-One wrapped around on the same track; allow another play count increment.
                playHistoryTracker.resetCurrentSession()
            }
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            val player = mediaSession?.player
            if (player != null && !isApplyingRestoredQueue) {
                if (player.mediaItemCount > 0) {
                    queueRestoreComplete = true
                }
                if (queueRestoreComplete) {
                    persistCurrentQueue()
                }
            }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            val player = mediaSession?.player
            if (repeatMode == Player.REPEAT_MODE_ONE) {
                resetCrossfadeFadeState(player)
            }
            persistCurrentQueue()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            persistCurrentQueue()
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            persistCurrentQueue()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val player = mediaSession?.player
            if (!isPlaying) {
                resetCrossfadeFadeState(player)
                persistCurrentQueue()
            } else if (player != null && currentTrack?.id != null &&
                playHistoryTracker.currentTrackId() == null
            ) {
                val resolvedDuration = currentTrack?.durationMs
                    ?: player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
                    ?: 0L
                playHistoryTracker.onTrackChanged(currentTrack?.id, resolvedDuration)
            }
            updatePlaybackMonitorLoop(isPlaying)
            serviceScope.launch {
                val track = resolveTrackAsync(mediaSession?.player?.currentMediaItem)
                currentTrack = track
                syncDspState()
                deps.widgetNotifier().onPlaybackStateChanged(track = track, isPlaying = isPlaying)
            }
        }
    }

    private val libraryCallback = object : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val root = browsableItem(
                mediaId = ROOT_ID,
                title = "Lunaria",
                mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
            )
            return Futures.immediateFuture(LibraryResult.ofItem(root, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            serviceScope.launch(Dispatchers.IO) {
                try {
                    val items = loadBrowseChildren(parentId)
                    val safePage = page.coerceAtLeast(0).toLong()
                    val safePageSize = pageSize.coerceAtLeast(1).toLong()
                    val from = (safePage * safePageSize).coerceAtMost(items.size.toLong()).toInt()
                    val to = ((safePage + 1L) * safePageSize).coerceAtMost(items.size.toLong()).toInt()
                    val sliced = items.subList(from, to)
                    future.set(LibraryResult.ofItemList(ImmutableList.copyOf(sliced), params))
                } catch (_: Throwable) {
                    future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN))
                }
            }
            return future
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val future = SettableFuture.create<LibraryResult<MediaItem>>()
            serviceScope.launch(Dispatchers.IO) {
                val idLong = mediaId.toLongOrNull()
                val item = if (idLong != null) {
                    deps.localMusicRepository().getTrackById(idLong)?.toMediaItem()
                } else {
                    categoryItemOrNull(mediaId)
                }
                if (item != null) {
                    future.set(LibraryResult.ofItem(item, null))
                } else {
                    future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
                }
            }
            return future
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val future = SettableFuture.create<MutableList<MediaItem>>()
            serviceScope.launch(Dispatchers.IO) {
                val repo = deps.localMusicRepository()
                val resolved = mutableListOf<MediaItem>()
                for (item in mediaItems) {
                    if (item.localConfiguration != null) {
                        resolved += item
                    } else {
                        val id = item.mediaId.toLongOrNull()
                        val track = id?.let { repo.getTrackById(it) }
                        resolved += track?.toMediaItem() ?: item
                    }
                }
                future.set(resolved)
            }
            return future
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = buildResumptionFuture()
    }

    override fun onCreate() {
        super.onCreate()
        deps = EntryPointAccessors.fromApplication(applicationContext, ServiceDeps::class.java)
        deps.usbBitPerfectManager().startMonitoring()

        currentMode = AudioOutputMode.DSP_FLOAT_32
        currentEqualizer = EqualizerSettings()
        deps.dspConfigStore().updateBypassed(currentMode == AudioOutputMode.PURIST_DIRECT_BIT_PERFECT)

        val initialPlayer = buildExoPlayer(currentMode)
        val builder = MediaLibrarySession.Builder(this, initialPlayer, libraryCallback)
        createSessionActivityPendingIntent()?.let { builder.setSessionActivity(it) }
        mediaSession = builder.build()

        observePreferences()
        observeRouteChanges()
        restorePersistedQueueAsync()
    }

    private fun buildExoPlayer(mode: AudioOutputMode): ExoPlayer {
        val configStore = if (mode == AudioOutputMode.DSP_FLOAT_32) deps.dspConfigStore() else null
        return ExoPlayer.Builder(
            this,
            LunariaRenderersFactory(this, audioOutputMode = mode, dspConfigStore = configStore),
        )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setPauseAtEndOfMediaItems(false)
            .build()
            .apply {
                addAnalyticsListener(analyticsListener)
                addListener(sessionPlayerListener)
            }
    }

    private fun observePreferences() {
        serviceScope.launch {
            deps.preferencesDataSource().preferences
                .map { it.audioOutputMode }
                .distinctUntilChanged()
                .collect { nextMode ->
                    if (nextMode != currentMode) {
                        switchAudioOutputMode(nextMode)
                    }
                }
        }
        serviceScope.launch {
            deps.preferencesDataSource().preferences
                .map { it.equalizer }
                .distinctUntilChanged()
                .collect { eq ->
                    currentEqualizer = eq
                    syncDspState()
                    val player = mediaSession?.player
                    if (!isCrossfadeEligible(player)) {
                        resetCrossfadeFadeState(player)
                    }
                }
        }
    }

    private fun syncDspState() {
        val track = currentTrack
        deps.dspConfigStore().updateAll(
            settings = currentEqualizer,
            bypassed = currentMode == AudioOutputMode.PURIST_DIRECT_BIT_PERFECT,
            trackReplayGainDb = track?.replayGainTrackDb,
            albumReplayGainDb = track?.replayGainAlbumDb,
            trackReplayGainPeak = track?.replayGainTrackPeak,
            albumReplayGainPeak = track?.replayGainAlbumPeak,
        )
        deps.signalPathTracker().updateEqualizerSettings(currentEqualizer)
    }

    private fun observeRouteChanges() {
        serviceScope.launch {
            deps.usbBitPerfectManager().usbDisconnectedEvents.collect {
                val player = mediaSession?.player
                if (player != null && player.isPlaying) {
                    player.pause()
                    deps.signalPathTracker().emitNotice("USB DAC disconnected — playback paused")
                }
                deps.signalPathTracker().onRouteChanged()
            }
        }
        serviceScope.launch {
            deps.usbBitPerfectManager().routeSnapshot
                .distinctUntilChanged { old, new -> old.capabilities == new.capabilities }
                .collect {
                    val resolved = resolveTrackAsync(mediaSession?.player?.currentMediaItem)
                currentTrack = resolved
                if (resolved != null && currentMode == AudioOutputMode.PURIST_DIRECT_BIT_PERFECT) {
                    deps.usbBitPerfectManager().applyMixerForTrack(currentMode, resolved)
                }
                deps.signalPathTracker().onRouteChanged()
            }
        }
    }

    private suspend fun switchAudioOutputMode(newMode: AudioOutputMode) {
        val session = mediaSession ?: return
        val initialItem = session.player.currentMediaItem
        val resolvedInitialTrack = resolveTrackAsync(initialItem)

        val oldPlayer = session.player
        val wasPlaying = oldPlayer.playWhenReady
        val currentIndex = oldPlayer.currentMediaItemIndex.takeIf { it != C.INDEX_UNSET } ?: 0
        val currentPositionMs = oldPlayer.currentPosition.coerceAtLeast(0L)
        val repeatMode = oldPlayer.repeatMode
        val shuffleEnabled = oldPlayer.shuffleModeEnabled
        val playbackParams = oldPlayer.playbackParameters
        val items = buildList(oldPlayer.mediaItemCount) {
            for (i in 0 until oldPlayer.mediaItemCount) {
                add(oldPlayer.getMediaItemAt(i))
            }
        }
        val track = if (oldPlayer.currentMediaItem?.mediaId == initialItem?.mediaId) {
            resolvedInitialTrack
        } else {
            oldPlayer.currentMediaItem?.toFallbackTrack()
        }
        currentTrack = track
        resetCrossfadeFadeState(oldPlayer)

        currentMode = newMode
        syncDspState()
        deps.usbBitPerfectManager().applyMixerForTrack(newMode, track)
        deps.signalPathTracker().updateTrackAndMode(track, newMode)

        val newPlayer = buildExoPlayer(newMode)
        newPlayer.repeatMode = repeatMode
        newPlayer.shuffleModeEnabled = shuffleEnabled
        newPlayer.playbackParameters = playbackParams
        newPlayer.volume = 1f
        if (items.isNotEmpty()) {
            newPlayer.setMediaItems(items, currentIndex.coerceIn(0, items.lastIndex), currentPositionMs)
            newPlayer.prepare()
            newPlayer.playWhenReady = wasPlaying
        }

        session.player = newPlayer
        updatePlaybackMonitorLoop(wasPlaying)
        (oldPlayer as? ExoPlayer)?.let {
            it.removeAnalyticsListener(analyticsListener)
            it.removeListener(sessionPlayerListener)
            it.release()
        }
    }

    private fun restorePersistedQueueAsync() {
        serviceScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching { deps.preferencesDataSource().persistedQueue.first() }.getOrNull()
            }
            if (saved != null && saved.trackIds.isNotEmpty()) {
                val tracks = withContext(Dispatchers.IO) {
                    deps.localMusicRepository().getTracksByIds(saved.trackIds)
                }
                val session = mediaSession
                val player = session?.player
                if (tracks.isNotEmpty() && player != null && player.mediaItemCount == 0) {
                    val safeIndex = saved.currentIndex.coerceIn(0, tracks.lastIndex)
                    val restoredTrack = tracks[safeIndex]
                    currentTrack = restoredTrack
                    isApplyingRestoredQueue = true
                    try {
                        player.repeatMode = toMedia3RepeatMode(saved.repeatMode)
                        player.shuffleModeEnabled = saved.shuffleEnabled
                        player.setPlaybackSpeed(saved.speed)
                        player.setMediaItems(
                            tracks.map { it.toMediaItem() },
                            safeIndex,
                            saved.positionMs.coerceAtLeast(0L),
                        )
                        player.prepare()
                        player.playWhenReady = false
                    } finally {
                        isApplyingRestoredQueue = false
                    }
                    playHistoryTracker.onTrackChanged(restoredTrack.id, restoredTrack.durationMs)
                    syncDspState()
                    deps.signalPathTracker().updateTrackAndMode(restoredTrack, currentMode)
                    deps.widgetNotifier().onPlaybackStateChanged(track = restoredTrack, isPlaying = false)
                }
            }
            queueRestoreComplete = true
        }
    }

    private fun buildResumptionFuture(): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        serviceScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val repo = deps.localMusicRepository()
                val saved = deps.preferencesDataSource().persistedQueue.first()
                val savedTracks = if (saved.trackIds.isNotEmpty()) {
                    repo.getTracksByIds(saved.trackIds)
                } else {
                    emptyList()
                }
                if (savedTracks.isNotEmpty()) {
                    val safeIndex = saved.currentIndex.coerceIn(0, savedTracks.lastIndex)
                    MediaSession.MediaItemsWithStartPosition(
                        savedTracks.map { it.toMediaItem() },
                        safeIndex,
                        saved.positionMs.coerceAtLeast(0L),
                    )
                } else {
                    val fallback = repo.recentTracks.first()
                    if (fallback.isNotEmpty()) {
                        MediaSession.MediaItemsWithStartPosition(fallback.map { it.toMediaItem() }, 0, 0L)
                    } else {
                        null
                    }
                }
            }.getOrNull()

            if (result != null) {
                future.set(result)
            } else {
                future.setException(IllegalStateException("No local tracks available for resumption"))
            }
        }
        return future
    }

    private fun updatePlaybackMonitorLoop(isPlaying: Boolean) {
        monitorJob?.cancel()
        monitorJob = null
        if (!isPlaying) return
        monitorJob = serviceScope.launch {
            var lastTickElapsed = SystemClock.elapsedRealtime()
            while (isActive) {
                val player = mediaSession?.player ?: break
                if (!player.isPlaying) break
                val now = SystemClock.elapsedRealtime()
                val deltaMs = (now - lastTickElapsed).coerceIn(0L, 1_500L)
                lastTickElapsed = now

                val recordedListenMs = playHistoryTracker.onPlayTick(deltaMs)
                val trackedId = playHistoryTracker.currentTrackId()
                if (recordedListenMs != null && trackedId != null) {
                    ioPersistScope.launch {
                        deps.localMusicRepository().recordTrackPlay(
                            trackId = trackedId,
                            playedAtMs = System.currentTimeMillis(),
                            listenedMs = recordedListenMs,
                        )
                    }
                }

                if (now - lastQueuePersistElapsedMs >= PERIODIC_QUEUE_SAVE_MS) {
                    lastQueuePersistElapsedMs = now
                    persistCurrentQueue()
                }

                val delayMs = updateCrossfadeFrame(player, now)
                delay(delayMs)
            }
        }
    }

    private fun updateCrossfadeFrame(player: Player, nowElapsedMs: Long): Long {
        if (!isCrossfadeEligible(player)) {
            resetCrossfadeFadeState(player)
            return MONITOR_IDLE_INTERVAL_MS
        }

        val totalCrossfadeMs = currentEqualizer.crossfadeDurationSec * 1_000L
        val halfFadeMs = (totalCrossfadeMs / 2L).coerceAtLeast(500L)

        // 1. Incoming fade-in right after automatic track transition
        if (fadeInStartElapsedMs > 0L) {
            val elapsed = nowElapsedMs - fadeInStartElapsedMs
            if (elapsed >= fadeInDurationMs) {
                fadeInStartElapsedMs = 0L
                fadeInDurationMs = 0L
                player.volume = 1f
            } else {
                player.volume = CrossfadeCurve.computeFadeInVolume(elapsed, fadeInDurationMs)
                return MONITOR_FADE_INTERVAL_MS
            }
        }

        // 2. Outgoing fade-out near end of track if another track is next
        val duration = player.duration
        if (duration == C.TIME_UNSET || !player.hasNextMediaItem()) {
            if (player.volume != 1f) player.volume = 1f
            return MONITOR_IDLE_INTERVAL_MS
        }

        val speed = player.playbackParameters.speed.coerceAtLeast(0.1f)
        val wallDurationMs = (duration / speed).toLong()
        if (wallDurationMs <= totalCrossfadeMs * 2L) {
            if (player.volume != 1f) player.volume = 1f
            return MONITOR_IDLE_INTERVAL_MS
        }

        val remainingMediaMs = (duration - player.currentPosition).coerceAtLeast(0L)
        val remainingWallMs = (remainingMediaMs / speed).toLong()
        if (remainingWallMs <= halfFadeMs) {
            pendingAutoCrossfadeFadeIn = true
            player.volume = CrossfadeCurve.computeFadeOutVolume(remainingWallMs, halfFadeMs)
            return MONITOR_FADE_INTERVAL_MS
        }

        if (player.volume != 1f) player.volume = 1f
        return if (remainingWallMs <= halfFadeMs + 800L) MONITOR_FADE_INTERVAL_MS else MONITOR_IDLE_INTERVAL_MS
    }

    private fun isCrossfadeEligible(player: Player?): Boolean =
        player != null &&
            currentMode == AudioOutputMode.DSP_FLOAT_32 &&
            currentEqualizer.crossfadeDurationSec > 0 &&
            player.repeatMode != Player.REPEAT_MODE_ONE

    private fun resetCrossfadeFadeState(player: Player?) {
        fadeInStartElapsedMs = 0L
        fadeInDurationMs = 0L
        pendingAutoCrossfadeFadeIn = false
        if (player != null && player.volume != 1f) {
            player.volume = 1f
        }
    }

    private fun captureQueueSnapshot(): PersistedQueueState? {
        if (!queueRestoreComplete || isApplyingRestoredQueue) return null
        val player = mediaSession?.player ?: return null
        val ids = buildList(player.mediaItemCount) {
            for (i in 0 until player.mediaItemCount) {
                player.getMediaItemAt(i).mediaId.toLongOrNull()?.let { add(it) }
            }
        }
        val currentIdx = player.currentMediaItemIndex.takeIf { it in ids.indices } ?: 0
        return PersistedQueueState(
            trackIds = ids,
            currentIndex = currentIdx,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            repeatMode = fromMedia3RepeatMode(player.repeatMode),
            shuffleEnabled = player.shuffleModeEnabled,
            speed = player.playbackParameters.speed,
        )
    }

    private fun persistCurrentQueue() {
        val snapshot = captureQueueSnapshot() ?: return
        lastQueuePersistElapsedMs = SystemClock.elapsedRealtime()
        ioPersistScope.launch {
            deps.preferencesDataSource().savePersistedQueue(snapshot)
        }
    }

    private suspend fun resolveTrackAsync(mediaItem: MediaItem?): Track? {
        if (mediaItem == null) return null
        val id = mediaItem.mediaId.toLongOrNull()
        val fromDb = if (id != null) {
            withContext(Dispatchers.IO) {
                deps.localMusicRepository().getTrackById(id)
            }
        } else {
            null
        }
        return fromDb ?: mediaItem.toFallbackTrack()
    }

    private suspend fun loadBrowseChildren(parentId: String): List<MediaItem> {
        val repo = deps.localMusicRepository()
        return when {
            parentId == ROOT_ID -> listOf(
                browsableItem(SONGS_ID, "Songs", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
                browsableItem(ALBUMS_ID, "Albums", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
                browsableItem(ARTISTS_ID, "Artists", MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS),
                browsableItem(PLAYLISTS_ID, "Playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
            )

            parentId == SONGS_ID -> repo.tracks.first().map { it.toMediaItem() }

            parentId == ALBUMS_ID -> repo.albums.first().map { album ->
                browsableItem(
                    mediaId = "$ALBUM_PREFIX${album.id}",
                    title = album.title,
                    mediaType = MediaMetadata.MEDIA_TYPE_ALBUM,
                    subtitle = "${album.artist} • ${album.trackCount} tracks",
                )
            }

            parentId.startsWith(ALBUM_PREFIX) -> {
                val albumId = parentId.removePrefix(ALBUM_PREFIX).toLongOrNull() ?: return emptyList()
                repo.tracks.first()
                    .filter { it.albumId == albumId }
                    .sortedWith(compareBy({ it.discNumber }, { it.trackNumber }))
                    .map { it.toMediaItem() }
            }

            parentId == ARTISTS_ID -> repo.artists.first().map { artist ->
                browsableItem(
                    mediaId = "$ARTIST_PREFIX${artist.name}",
                    title = artist.name,
                    mediaType = MediaMetadata.MEDIA_TYPE_ARTIST,
                    subtitle = "${artist.trackCount} tracks",
                )
            }

            parentId.startsWith(ARTIST_PREFIX) -> {
                val artistName = parentId.removePrefix(ARTIST_PREFIX)
                repo.tracks.first()
                    .filter {
                        it.albumArtist.ifBlank { it.artist }.trim().ifEmpty { "Unknown Artist" }
                            .equals(artistName, ignoreCase = true)
                    }
                    .map { it.toMediaItem() }
            }

            parentId == PLAYLISTS_ID -> repo.playlists.first().map { playlist ->
                browsableItem(
                    mediaId = "$PLAYLIST_PREFIX${playlist.id}",
                    title = playlist.name,
                    mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST,
                    subtitle = "${playlist.trackCount} tracks",
                )
            }

            parentId.startsWith(PLAYLIST_PREFIX) -> {
                val playlistId = parentId.removePrefix(PLAYLIST_PREFIX).toLongOrNull() ?: return emptyList()
                repo.getPlaylistTracks(playlistId).map { it.toMediaItem() }
            }

            else -> emptyList()
        }
    }

    private fun categoryItemOrNull(mediaId: String): MediaItem? = when (mediaId) {
        ROOT_ID -> browsableItem(ROOT_ID, "Lunaria", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
        SONGS_ID -> browsableItem(SONGS_ID, "Songs", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
        ALBUMS_ID -> browsableItem(ALBUMS_ID, "Albums", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS)
        ARTISTS_ID -> browsableItem(ARTISTS_ID, "Artists", MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS)
        PLAYLISTS_ID -> browsableItem(PLAYLISTS_ID, "Playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS)
        else -> null
    }

    private fun browsableItem(
        mediaId: String,
        title: String,
        mediaType: Int,
        subtitle: String? = null,
    ): MediaItem {
        val meta = MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(mediaType)
            .build()
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(meta)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        persistCurrentQueue()
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        persistCurrentQueue()
        monitorJob?.cancel()
        serviceScope.cancel()
        deps.usbBitPerfectManager().stopMonitoring()
        mediaSession?.run {
            (player as? ExoPlayer)?.let {
                it.volume = 1f
                it.removeAnalyticsListener(analyticsListener)
                it.removeListener(sessionPlayerListener)
            }
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private fun createSessionActivityPendingIntent(): PendingIntent? {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
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
        const val ROOT_ID = "lunaria_root"
        const val SONGS_ID = "lunaria_songs"
        const val ALBUMS_ID = "lunaria_albums"
        const val ARTISTS_ID = "lunaria_artists"
        const val PLAYLISTS_ID = "lunaria_playlists"
        const val ALBUM_PREFIX = "lunaria_album:"
        const val ARTIST_PREFIX = "lunaria_artist:"
        const val PLAYLIST_PREFIX = "lunaria_playlist:"
        const val MONITOR_IDLE_INTERVAL_MS = 250L
        const val MONITOR_FADE_INTERVAL_MS = 50L
        const val PERIODIC_QUEUE_SAVE_MS = 5_000L
    }
}
