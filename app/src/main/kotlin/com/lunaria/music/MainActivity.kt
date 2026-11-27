package com.lunaria.music

import android.Manifest
import android.content.Context
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.lunaria.core.designsystem.theme.LunariaTheme
import com.lunaria.core.mediascanner.permission.hasNotificationPermission
import com.lunaria.core.mediascanner.permission.isNotificationPermissionRequired
import com.lunaria.core.model.AudioPermissionStatus
import com.lunaria.core.model.ThemeMode
import com.lunaria.feature.player.PlayerActions
import com.lunaria.music.permission.rememberAudioPermissionController
import com.lunaria.music.ui.LunariaApp
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
            DisposableEffect(darkTheme) {
                val barStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme }
                enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Keep our own bar colours instead of the system's translucent scrim.
                    window.isNavigationBarContrastEnforced = false
                }
                onDispose {}
            }

            val permission = rememberAudioPermissionController()
            LaunchedEffect(permission.status) {
                if (permission.status == AudioPermissionStatus.Granted) {
                    viewModel.onAudioPermissionGranted()
                }
            }

            // Ask for POST_NOTIFICATIONS once when playback starts on Android 13+; playback works either way.
            val notificationLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { }
            LaunchedEffect(playbackState.isPlaying) {
                if (playbackState.isPlaying &&
                    isNotificationPermissionRequired() &&
                    !hasNotificationPermission(this@MainActivity)
                ) {
                    val alreadyAsked = withContext(Dispatchers.IO) {
                        val prefs = getSharedPreferences("lunaria_permissions", Context.MODE_PRIVATE)
                        val asked = prefs.getBoolean("asked_post_notifications", false)
                        if (!asked) {
                            prefs.edit().putBoolean("asked_post_notifications", true).apply()
                        }
                        asked
                    }
                    if (!alreadyAsked) {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
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

            LunariaTheme(darkTheme = darkTheme, dynamicColor = preferences.useDynamicColor) {
                LunariaApp(
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
