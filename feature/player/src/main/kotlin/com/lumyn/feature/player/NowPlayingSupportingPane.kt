package com.lumyn.feature.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lumyn.core.audioengine.QueueOperations
import com.lumyn.core.designsystem.component.LumynArtwork
import com.lumyn.core.designsystem.icon.LumynIcons
import com.lumyn.core.designsystem.theme.LumynMotionTokens
import com.lumyn.core.designsystem.theme.nestedCornerRadius
import com.lumyn.core.model.PlaybackState
import com.lumyn.core.model.SleepTimerState

/**
 * Now Playing as a persistent supporting pane for expanded windows (840 dp and wider).
 * Uses the same album-art dynamic colour & transport controls as the full-screen phone player.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NowPlayingSupportingPane(
    state: PlaybackState,
    signalSummary: String,
    lyricsState: LyricsUiState = LyricsUiState(),
    useAlbumArtTheme: Boolean = true,
    darkTheme: Boolean = false,
    actions: PlayerActions = PlayerActions(),
    modifier: Modifier = Modifier,
) {
    var showQueueSheet by rememberSaveable { mutableStateOf(false) }
    var showTimerSheet by rememberSaveable { mutableStateOf(false) }
    var showSpeedSheet by rememberSaveable { mutableStateOf(false) }
    var showSignalPathSheet by rememberSaveable { mutableStateOf(false) }
    var showLyricsSheet by rememberSaveable { mutableStateOf(false) }

    AnimatedAlbumArtTheme(
        track = state.currentTrack,
        enabled = useAlbumArtTheme,
        darkTheme = darkTheme,
    ) {
        val track = state.currentTrack
        val hasTrack = track != null
        val playCorner by animateDpAsState(
            targetValue = if (state.isPlaying) 20.dp else 36.dp,
            animationSpec = LumynMotionTokens.spatialDefault(),
            label = "playPauseCorner",
        )

        if (showQueueSheet) {
            QueueSheet(
                queue = state.queue,
                currentIndex = state.currentIndex,
                onSkipToIndex = actions.onSkipToQueueIndex,
                onMoveItem = actions.onMoveQueueItem,
                onRemoveItem = actions.onRemoveQueueItem,
                onSaveAsPlaylist = actions.onSaveQueueAsPlaylist,
                onDismiss = { showQueueSheet = false },
            )
        }
        if (showTimerSheet) {
            SleepTimerSheet(
                sleepTimer = state.sleepTimer,
                onSetCountdown = actions.onSetSleepCountdown,
                onSetEndOfTrack = actions.onSetSleepEndOfTrack,
                onCancel = actions.onCancelSleepTimer,
                onDismiss = { showTimerSheet = false },
            )
        }
        if (showSpeedSheet) {
            PlaybackSpeedSheet(
                currentSpeed = state.speed,
                onSelectSpeed = actions.onSelectSpeed,
                onDismiss = { showSpeedSheet = false },
            )
        }
        if (showSignalPathSheet) {
            SignalPathSheet(
                signalPath = state.signalPath,
                onSelectOutputMode = actions.onSelectAudioOutputMode,
                onDismiss = { showSignalPathSheet = false },
            )
        }
        if (showLyricsSheet) {
            LyricsSheet(
                lyricsState = lyricsState,
                positionMs = state.positionMs,
                onSeek = actions.onSeek,
                onDismiss = { showLyricsSheet = false },
            )
        }

        Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                LumynArtwork(
                    artworkUri = track?.artworkUri,
                    contentDescription = track?.album,
                    cornerRadius = nestedCornerRadius(outerRadius = 28.dp, padding = 0.dp),
                    fallbackIconSize = 72.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = track?.title ?: "No track playing",
                            style = MaterialTheme.typography.titleLargeEmphasized,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2_000),
                        )
                        Text(
                            text = track?.artist ?: "Your local collection is ready",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (track != null) {
                        IconToggleButton(
                            checked = track.isFavorite,
                            onCheckedChange = { actions.onToggleFavorite(track) },
                            shapes = IconButtonDefaults.toggleableShapes(),
                            modifier = Modifier.semantics {
                                stateDescription = if (track.isFavorite) "Favorited" else "Not favorited"
                            },
                        ) {
                            Icon(
                                painter = painterResource(
                                    if (track.isFavorite) LumynIcons.FavoriteFilled else LumynIcons.FavoriteOutlined,
                                ),
                                contentDescription = if (track.isFavorite) "Remove from favorites" else "Add to favorites",
                            )
                        }
                        IconButton(
                            onClick = { actions.onAddToPlaylist(track) },
                            shapes = IconButtonDefaults.shapes(),
                        ) {
                            Icon(
                                painter = painterResource(LumynIcons.PlaylistAdd),
                                contentDescription = "Add to playlist",
                            )
                        }
                        IconButton(
                            onClick = { actions.onEditTags(track) },
                            shapes = IconButtonDefaults.shapes(),
                        ) {
                            Icon(
                                painter = painterResource(LumynIcons.Edit),
                                contentDescription = "Edit tags",
                            )
                        }
                    }
                }

                Surface(
                    onClick = { showSignalPathSheet = true },
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            painter = painterResource(LumynIcons.GraphicEq),
                            contentDescription = "View audio signal path",
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = track?.audioSpec?.formatQualitySummary() ?: signalSummary,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }

                state.errorMessage?.let { errorMsg ->
                    PlaybackErrorBanner(
                        message = errorMsg,
                        onSkipNext = actions.onNext,
                        onDismiss = actions.onDismissError,
                    )
                }

                PlaybackSeekbar(
                    positionMs = state.positionMs,
                    durationMs = state.durationMs,
                    enabled = hasTrack,
                    onSeek = actions.onSeek,
                    isPlaying = state.isPlaying,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    ShuffleButton(
                        enabled = hasTrack,
                        checked = state.shuffleEnabled,
                        onToggle = actions.onToggleShuffle,
                    )
                    FilledTonalIconButton(
                        onClick = actions.onPrevious,
                        enabled = state.hasPrevious,
                        shapes = IconButtonDefaults.shapes(),
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            painter = painterResource(LumynIcons.SkipPrevious),
                            contentDescription = "Previous track",
                        )
                    }
                    FilledIconButton(
                        onClick = actions.onPlayPause,
                        enabled = hasTrack,
                        shape = RoundedCornerShape(playCorner),
                        modifier = Modifier.size(64.dp),
                    ) {
                        Icon(
                            painter = painterResource(if (state.isPlaying) LumynIcons.Pause else LumynIcons.PlayArrow),
                            contentDescription = if (state.isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(30.dp),
                        )
                    }
                    FilledTonalIconButton(
                        onClick = actions.onNext,
                        enabled = state.hasNext,
                        shapes = IconButtonDefaults.shapes(),
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            painter = painterResource(LumynIcons.SkipNext),
                            contentDescription = "Next track",
                        )
                    }
                    RepeatButton(
                        enabled = hasTrack,
                        repeatMode = state.repeatMode,
                        onCycle = actions.onCycleRepeat,
                    )
                }

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(onClick = { showLyricsSheet = true }, enabled = hasTrack) { Text("Lyrics") }
                    TextButton(onClick = actions.onOpenEqualizer) { Text("EQ") }
                    TextButton(onClick = { showTimerSheet = true }) {
                        val timerText = when (val timer = state.sleepTimer) {
                            SleepTimerState.Off -> "Timer"
                            SleepTimerState.EndOfTrack -> "End of track"
                            is SleepTimerState.Countdown -> QueueOperations.formatSleepTimerRemaining(timer.remainingMs)
                        }
                        Text(timerText)
                    }
                    TextButton(onClick = { showSpeedSheet = true }) {
                        Text(formatSpeedLabel(state.speed))
                    }
                    TextButton(onClick = { showQueueSheet = true }) {
                        Text("Queue (${state.queue.size})")
                    }
                }
            }
        }
    }
}
