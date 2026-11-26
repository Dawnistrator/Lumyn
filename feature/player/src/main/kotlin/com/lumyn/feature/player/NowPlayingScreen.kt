package com.lumyn.feature.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lumyn.core.audioengine.QueueOperations
import com.lumyn.core.designsystem.component.LumynArtwork
import com.lumyn.core.designsystem.component.formatDurationMs
import com.lumyn.core.designsystem.icon.LumynIcons
import com.lumyn.core.designsystem.theme.LumynMotionTokens
import com.lumyn.core.model.PlaybackState
import com.lumyn.core.model.RepeatMode
import com.lumyn.core.model.SleepTimerState

@Composable
fun NowPlayingScreen(
    state: PlaybackState,
    lyricsState: LyricsUiState = LyricsUiState(),
    useAlbumArtTheme: Boolean,
    darkTheme: Boolean,
    actions: PlayerActions,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onCollapse)

    var showQueueSheet by rememberSaveable { mutableStateOf(false) }
    var showTimerSheet by rememberSaveable { mutableStateOf(false) }
    var showSpeedSheet by rememberSaveable { mutableStateOf(false) }
    var showSignalPathSheet by rememberSaveable { mutableStateOf(false) }
    var showLyricsSheet by rememberSaveable { mutableStateOf(false) }
    var overflowExpanded by remember { mutableStateOf(false) }

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
            label = "nowPlayingPlayCorner",
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

        Surface(
            modifier = modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Top + WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                        ),
                    )
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onCollapse) {
                        Icon(
                            painter = painterResource(LumynIcons.KeyboardArrowDown),
                            contentDescription = "Collapse player",
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "Now playing",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = track?.album ?: "Local library",
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { showQueueSheet = true }) {
                        Icon(
                            painter = painterResource(LumynIcons.QueueMusic),
                            contentDescription = "Open queue",
                        )
                    }
                    if (track != null) {
                        Box {
                            IconButton(onClick = { overflowExpanded = true }) {
                                Icon(
                                    painter = painterResource(LumynIcons.MoreVert),
                                    contentDescription = "More track options",
                                )
                            }
                            DropdownMenu(
                                expanded = overflowExpanded,
                                onDismissRequest = { overflowExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Add to playlist") },
                                    leadingIcon = {
                                        Icon(painterResource(LumynIcons.PlaylistAdd), contentDescription = null)
                                    },
                                    onClick = {
                                        overflowExpanded = false
                                        actions.onAddToPlaylist(track)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Edit tags") },
                                    leadingIcon = {
                                        Icon(painterResource(LumynIcons.Edit), contentDescription = null)
                                    },
                                    onClick = {
                                        overflowExpanded = false
                                        actions.onEditTags(track)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Audio signal path") },
                                    leadingIcon = {
                                        Icon(painterResource(LumynIcons.GraphicEq), contentDescription = null)
                                    },
                                    onClick = {
                                        overflowExpanded = false
                                        showSignalPathSheet = true
                                    },
                                )
                            }
                        }
                    }
                }

                state.errorMessage?.let { errorMsg ->
                    Spacer(Modifier.height(6.dp))
                    PlaybackErrorBanner(
                        message = errorMsg,
                        onSkipNext = actions.onNext,
                        onDismiss = actions.onDismissError,
                    )
                }

                Spacer(Modifier.height(8.dp))

                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    LumynArtwork(
                        artworkUri = track?.artworkUri,
                        contentDescription = track?.album,
                        cornerRadius = 24.dp,
                        fallbackIconSize = 72.dp,
                        modifier = Modifier
                            .widthIn(max = 280.dp)
                            .fillMaxWidth(0.76f)
                            .aspectRatio(1f),
                    )
                }

                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track?.title ?: "No track playing",
                            style = MaterialTheme.typography.headlineSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = track?.artist ?: "Select a track from your library",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (track != null) {
                        IconToggleButton(
                            checked = track.isFavorite,
                            onCheckedChange = { actions.onToggleFavorite(track) },
                            modifier = Modifier.semantics {
                                stateDescription = if (track.isFavorite) "Favorited" else "Not favorited"
                            },
                        ) {
                            Icon(
                                painter = painterResource(
                                    if (track.isFavorite) LumynIcons.FavoriteFilled else LumynIcons.FavoriteOutlined,
                                ),
                                contentDescription = if (track.isFavorite) "Remove from favorites" else "Add to favorites",
                                tint = if (track.isFavorite) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }

                if (track != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            onClick = { showSignalPathSheet = true },
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    painter = painterResource(LumynIcons.GraphicEq),
                                    contentDescription = "View audio signal path",
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = track.audioSpec.formatQualitySummary(),
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                PlaybackSeekbar(
                    positionMs = state.positionMs,
                    durationMs = state.durationMs,
                    enabled = hasTrack,
                    onSeek = actions.onSeek,
                )

                Spacer(Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ShuffleButton(
                        enabled = hasTrack,
                        checked = state.shuffleEnabled,
                        onToggle = actions.onToggleShuffle,
                    )
                    FilledTonalIconButton(
                        onClick = actions.onPrevious,
                        enabled = state.hasPrevious,
                        modifier = Modifier.size(56.dp),
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
                        modifier = Modifier.size(72.dp),
                    ) {
                        Icon(
                            painter = painterResource(
                                if (state.isPlaying) LumynIcons.Pause else LumynIcons.PlayArrow,
                            ),
                            contentDescription = if (state.isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(34.dp),
                        )
                    }
                    FilledTonalIconButton(
                        onClick = actions.onNext,
                        enabled = state.hasNext,
                        modifier = Modifier.size(56.dp),
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

                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    UtilityActionButton(
                        iconRes = LumynIcons.Lyrics,
                        label = "Lyrics",
                        enabled = hasTrack,
                        onClick = { showLyricsSheet = true },
                    )
                    UtilityActionButton(
                        iconRes = LumynIcons.Equalizer,
                        label = "EQ",
                        enabled = true,
                        onClick = actions.onOpenEqualizer,
                    )
                    val timerText = when (val timer = state.sleepTimer) {
                        SleepTimerState.Off -> "Timer"
                        SleepTimerState.EndOfTrack -> "Track"
                        is SleepTimerState.Countdown -> QueueOperations.formatSleepTimerRemaining(timer.remainingMs)
                    }
                    UtilityActionButton(
                        iconRes = LumynIcons.Timer,
                        label = timerText,
                        enabled = true,
                        onClick = { showTimerSheet = true },
                    )
                    UtilityActionButton(
                        iconRes = LumynIcons.Speed,
                        label = formatSpeedLabel(state.speed),
                        enabled = true,
                        onClick = { showSpeedSheet = true },
                    )
                    UtilityActionButton(
                        iconRes = LumynIcons.QueueMusic,
                        label = if (state.queue.isEmpty()) "Queue" else "Queue (${state.queue.size})",
                        enabled = true,
                        onClick = { showQueueSheet = true },
                    )
                }
            }
        }
    }
}

@Composable
private fun UtilityActionButton(
    iconRes: Int,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    Column(
        modifier = Modifier
            .defaultMinSize(minWidth = 52.dp, minHeight = 48.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = label,
            tint = contentColor,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun PlaybackSeekbar(
    positionMs: Long,
    durationMs: Long,
    enabled: Boolean,
    onSeek: (Long) -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragPos by remember { mutableFloatStateOf(0f) }
    val safeDuration = durationMs.coerceAtLeast(1L)
    val displayedMs = if (dragging) dragPos.toLong() else positionMs.coerceIn(0L, safeDuration)

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = displayedMs.toFloat(),
            onValueChange = {
                dragging = true
                dragPos = it
            },
            onValueChangeFinished = {
                dragging = false
                onSeek(dragPos.toLong())
            },
            valueRange = 0f..safeDuration.toFloat(),
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatDurationMs(displayedMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatDurationMs(durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun ShuffleButton(
    enabled: Boolean,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    IconToggleButton(
        checked = checked,
        onCheckedChange = { onToggle() },
        enabled = enabled,
        modifier = Modifier.semantics {
            stateDescription = if (checked) "Shuffle on" else "Shuffle off"
        },
    ) {
        Icon(
            painter = painterResource(LumynIcons.Shuffle),
            contentDescription = "Toggle shuffle",
            tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun RepeatButton(
    enabled: Boolean,
    repeatMode: RepeatMode,
    onCycle: () -> Unit,
) {
    val active = repeatMode != RepeatMode.OFF
    IconToggleButton(
        checked = active,
        onCheckedChange = { onCycle() },
        enabled = enabled,
        modifier = Modifier.semantics {
            stateDescription = when (repeatMode) {
                RepeatMode.OFF -> "Repeat off"
                RepeatMode.ALL -> "Repeat all"
                RepeatMode.ONE -> "Repeat one"
            }
        },
    ) {
        Icon(
            painter = painterResource(
                if (repeatMode == RepeatMode.ONE) LumynIcons.RepeatOne else LumynIcons.Repeat,
            ),
            contentDescription = "Repeat mode",
            tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun PlaybackErrorBanner(
    message: String,
    onSkipNext: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onSkipNext) { Text("Skip") }
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}
