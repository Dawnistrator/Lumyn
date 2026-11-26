package com.lumyn.music

import android.Manifest
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumyn.core.designsystem.theme.LumynTheme
import com.lumyn.core.mediascanner.permission.hasNotificationPermission
import com.lumyn.core.mediascanner.permission.isNotificationPermissionRequired
import com.lumyn.core.model.AudioPermissionStatus
import com.lumyn.core.model.ThemeMode
import com.lumyn.feature.player.PlayerActions
import com.lumyn.music.permission.rememberAudioPermissionController
import com.lumyn.music.ui.LumynApp
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val preferences by viewModel.preferences.collectAsStateWithLifecycle()
            val playlists by viewModel.playlists.collectAsStateWithLifecycle()
            val capabilities by viewModel.capabilities.collectAsStateWithLifecycle()
            val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
            val lyricsState by viewModel.lyricsState.collectAsStateWithLifecycle()
            val darkTheme = when (preferences.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            // Re-apply system bar styling when the user switches theme in Settings,
            // so status and navigation bar icons stay legible.
            SideEffect {
                val barStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme }
                enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Keep our own bar colours instead of the system's translucent scrim.
                    window.isNavigationBarContrastEnforced = false
                }
            }

            val permission = rememberAudioPermissionController()
            LaunchedEffect(permission.status) {
                if (permission.status == AudioPermissionStatus.Granted) {
                    viewModel.onAudioPermissionGranted()
                }
            }

            // Ask for POST_NOTIFICATIONS once when playback starts on Android 13+; playback works either way.
            var askedNotification by rememberSaveable { mutableStateOf(false) }
            val notificationLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { }
            LaunchedEffect(playbackState.isPlaying) {
                if (playbackState.isPlaying &&
                    !askedNotification &&
                    isNotificationPermissionRequired() &&
                    !hasNotificationPermission(this@MainActivity)
                ) {
                    askedNotification = true
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            val snackbarHostState = remember { SnackbarHostState() }
            LaunchedEffect(viewModel) {
                viewModel.snackbarMessages.collect { message ->
                    snackbarHostState.showSnackbar(message)
                }
            }

            val playerActions = remember(viewModel) {
                PlayerActions(
                    onPlayPause = viewModel::togglePlayPause,
                    onNext = viewModel::skipToNext,
                    onPrevious = viewModel::skipToPrevious,
                    onSeek = viewModel::seekTo,
                    onToggleShuffle = viewModel::toggleShuffle,
                    onCycleRepeat = viewModel::cycleRepeatMode,
                    onToggleFavorite = viewModel::toggleFavorite,
                    onSelectSpeed = viewModel::setPlaybackSpeed,
                    onSetSleepCountdown = viewModel::setSleepCountdown,
                    onSetSleepEndOfTrack = viewModel::setSleepEndOfTrack,
                    onCancelSleepTimer = viewModel::cancelSleepTimer,
                    onSkipToQueueIndex = viewModel::skipToQueueIndex,
                    onMoveQueueItem = viewModel::moveQueueItem,
                    onRemoveQueueItem = viewModel::removeQueueItem,
                    onSaveQueueAsPlaylist = viewModel::saveQueueAsPlaylist,
                    onSelectAudioOutputMode = viewModel::setAudioOutputMode,
                    onDismissError = viewModel::clearPlaybackError,
                )
            }

            LumynTheme(darkTheme = darkTheme, dynamicColor = preferences.useDynamicColor) {
                LumynApp(
                    audioPermission = permission.status,
                    onPermissionAction = permission.onAction,
                    capabilities = capabilities,
                    playbackState = playbackState,
                    playlists = playlists,
                    lyricsState = lyricsState,
                    useAlbumArtTheme = preferences.useAlbumArtTheme,
                    darkTheme = darkTheme,
                    playerActions = playerActions,
                    onPlayTracks = viewModel::playTracks,
                    onPlayNext = viewModel::playNext,
                    onAddToQueue = viewModel::addToQueue,
                    onAddTracksToQueue = viewModel::addTracksToQueue,
                    onAddTrackToPlaylist = viewModel::addTrackToPlaylist,
                    onCreatePlaylistWithTrack = viewModel::createPlaylistWithTrack,
                    onSaveTrackTags = viewModel::saveTrackTags,
                    snackbarHostState = snackbarHostState,
                )
            }
        }
    }
}
