package com.lumyn.feature.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.lumyn.core.audioengine.QueueOperations
import com.lumyn.core.designsystem.component.LumynArtwork
import com.lumyn.core.designsystem.component.formatDurationMs
import com.lumyn.core.designsystem.icon.LumynIcons
import com.lumyn.core.designsystem.theme.LumynMotionTokens
import com.lumyn.core.model.PlaybackState
import com.lumyn.core.model.RepeatMode
import com.lumyn.core.model.SleepTimerState
import com.lumyn.core.model.Track
import kotlin.math.PI
import kotlin.math.sin

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
            targetValue = if (state.isPlaying) 22.dp else 36.dp,
            animationSpec = LumynMotionTokens.spatialDefault(),
            label = "nowPlayingPlayCorner",
        )
        val artworkScale by animateFloatAsState(
            targetValue = if (state.isPlaying) 1.0f else 0.92f,
            animationSpec = LumynMotionTokens.spatialDefault(),
            label = "nowPlayingArtworkScale",
        )
        val artworkCorner by animateDpAsState(
            targetValue = if (state.isPlaying) 24.dp else 18.dp,
            animationSpec = LumynMotionTokens.spatialDefault(),
            label = "nowPlayingArtworkCorner",
        )
        val artworkElevation by animateDpAsState(
            targetValue = if (state.isPlaying) 14.dp else 3.dp,
            animationSpec = LumynMotionTokens.spatialDefault(),
            label = "nowPlayingArtworkElevation",
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

        val ambientBrush = Brush.verticalGradient(
            colors = listOf(
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f),
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.14f),
                MaterialTheme.colorScheme.surface,
                MaterialTheme.colorScheme.surface,
            ),
        )

        Surface(
            modifier = modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ambientBrush),
            ) {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(
                                WindowInsetsSides.Top + WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                            ),
                        ),
                ) {
                    val useFitLayout = maxHeight >= 560.dp
                    val verticalGap = if (maxHeight > 700.dp) 14.dp else 10.dp
                    val rootModifier = if (useFitLayout) {
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp, vertical = 8.dp)
                    } else {
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 8.dp)
                    }

                    Column(
                        modifier = rootModifier,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        NowPlayingTopBar(
                            albumTitle = track?.album ?: "Local library",
                            track = track,
                            overflowExpanded = overflowExpanded,
                            onCollapse = onCollapse,
                            onExpandOverflow = { overflowExpanded = true },
                            onDismissOverflow = { overflowExpanded = false },
                            onAddToPlaylist = {
                                overflowExpanded = false
                                track?.let(actions.onAddToPlaylist)
                            },
                            onEditTags = {
                                overflowExpanded = false
                                track?.let(actions.onEditTags)
                            },
                            onOpenSignalPath = {
                                overflowExpanded = false
                                showSignalPathSheet = true
                            },
                        )

                        state.errorMessage?.let { errorMsg ->
                            Spacer(Modifier.height(6.dp))
                            PlaybackErrorBanner(
                                message = errorMsg,
                                onSkipNext = actions.onNext,
                                onDismiss = actions.onDismissError,
                            )
                        }

                        if (useFitLayout) {
                            BoxWithConstraints(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                val artSize = min(min(maxWidth, maxHeight), 360.dp)
                                ArtworkStageCard(
                                    track = track,
                                    artSize = artSize,
                                    scale = artworkScale,
                                    cornerRadius = artworkCorner,
                                    elevation = artworkElevation,
                                )
                            }
                        } else {
                            Spacer(Modifier.height(8.dp))
                            ArtworkStageCard(
                                track = track,
                                artSize = 200.dp,
                                scale = artworkScale,
                                cornerRadius = artworkCorner,
                                elevation = artworkElevation,
                            )
                            Spacer(Modifier.height(12.dp))
                        }

                        TrackHeadlineRow(track = track)

                        if (track != null) {
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(
                                    onClick = { showSignalPathSheet = true },
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.tertiaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier
                                        .weight(1f, fill = false)
                                        .padding(end = 12.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
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
                                            style = MaterialTheme.typography.labelMediumEmphasized,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                FilledTonalIconToggleButton(
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
                                        contentDescription = if (track.isFavorite) {
                                            "Remove from favorites"
                                        } else {
                                            "Add to favorites"
                                        },
                                        tint = if (track.isFavorite) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(verticalGap))

                        PlaybackSeekbar(
                            positionMs = state.positionMs,
                            durationMs = state.durationMs,
                            enabled = hasTrack,
                            onSeek = actions.onSeek,
                            isPlaying = state.isPlaying,
                        )

                        Spacer(Modifier.height(verticalGap))

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
                                shapes = IconButtonDefaults.shapes(),
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
                                modifier = Modifier
                                    .width(88.dp)
                                    .height(72.dp),
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
                                shapes = IconButtonDefaults.shapes(),
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

                        Spacer(Modifier.height(verticalGap))

                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.extraLarge,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            tonalElevation = 1.dp,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 6.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                UtilityActionButton(
                                    iconRes = LumynIcons.Lyrics,
                                    label = "Lyrics",
                                    enabled = hasTrack,
                                    active = false,
                                    onClick = { showLyricsSheet = true },
                                    modifier = Modifier.defaultMinSize(minWidth = 52.dp, minHeight = 48.dp),
                                )
                                UtilityActionButton(
                                    iconRes = LumynIcons.Equalizer,
                                    label = "EQ",
                                    enabled = true,
                                    active = false,
                                    onClick = actions.onOpenEqualizer,
                                    modifier = Modifier.defaultMinSize(minWidth = 52.dp, minHeight = 48.dp),
                                )
                                val timerText = when (val timer = state.sleepTimer) {
                                    SleepTimerState.Off -> "Timer"
                                    SleepTimerState.EndOfTrack -> "Track"
                                    is SleepTimerState.Countdown ->
                                        QueueOperations.formatSleepTimerRemaining(timer.remainingMs)
                                }
                                UtilityActionButton(
                                    iconRes = LumynIcons.Timer,
                                    label = timerText,
                                    enabled = true,
                                    active = state.sleepTimer != SleepTimerState.Off,
                                    onClick = { showTimerSheet = true },
                                    modifier = Modifier.defaultMinSize(minWidth = 52.dp, minHeight = 48.dp),
                                )
                                UtilityActionButton(
                                    iconRes = LumynIcons.Speed,
                                    label = formatSpeedLabel(state.speed),
                                    enabled = true,
                                    active = kotlin.math.abs(state.speed - 1.0f) >= 0.02f,
                                    onClick = { showSpeedSheet = true },
                                    modifier = Modifier.defaultMinSize(minWidth = 52.dp, minHeight = 48.dp),
                                )
                                UtilityActionButton(
                                    iconRes = LumynIcons.QueueMusic,
                                    label = if (state.queue.isEmpty()) "Queue" else "Queue (${state.queue.size})",
                                    enabled = true,
                                    active = false,
                                    onClick = { showQueueSheet = true },
                                    modifier = Modifier.defaultMinSize(minWidth = 52.dp, minHeight = 48.dp),
                                )
                            }
                        }

                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NowPlayingTopBar(
    albumTitle: String,
    track: Track?,
    overflowExpanded: Boolean,
    onCollapse: () -> Unit,
    onExpandOverflow: () -> Unit,
    onDismissOverflow: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onEditTags: () -> Unit,
    onOpenSignalPath: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            IconButton(
                onClick = onCollapse,
                shapes = IconButtonDefaults.shapes(),
            ) {
                Icon(
                    painter = painterResource(LumynIcons.KeyboardArrowDown),
                    contentDescription = "Collapse player",
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Now playing",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = albumTitle,
                style = MaterialTheme.typography.titleSmallEmphasized,
                maxLines = 1,
                modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2_000),
            )
        }
        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            if (track != null) {
                IconButton(
                    onClick = onExpandOverflow,
                    shapes = IconButtonDefaults.shapes(),
                ) {
                    Icon(
                        painter = painterResource(LumynIcons.MoreVert),
                        contentDescription = "More track options",
                    )
                }
                DropdownMenu(
                    expanded = overflowExpanded,
                    onDismissRequest = onDismissOverflow,
                ) {
                    DropdownMenuItem(
                        text = { Text("Add to playlist") },
                        leadingIcon = {
                            Icon(painterResource(LumynIcons.PlaylistAdd), contentDescription = null)
                        },
                        onClick = onAddToPlaylist,
                    )
                    DropdownMenuItem(
                        text = { Text("Edit tags") },
                        leadingIcon = {
                            Icon(painterResource(LumynIcons.Edit), contentDescription = null)
                        },
                        onClick = onEditTags,
                    )
                    DropdownMenuItem(
                        text = { Text("Audio signal path") },
                        leadingIcon = {
                            Icon(painterResource(LumynIcons.GraphicEq), contentDescription = null)
                        },
                        onClick = onOpenSignalPath,
                    )
                }
            }
        }
    }
}

@Composable
private fun ArtworkStageCard(
    track: Track?,
    artSize: Dp,
    scale: Float,
    cornerRadius: Dp,
    elevation: Dp,
) {
    Surface(
        modifier = Modifier
            .size(artSize)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        shape = RoundedCornerShape(cornerRadius),
        shadowElevation = elevation,
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        LumynArtwork(
            artworkUri = track?.artworkUri,
            contentDescription = track?.album,
            cornerRadius = cornerRadius,
            fallbackIconSize = 80.dp,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TrackHeadlineRow(track: Track?) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = track?.title ?: "No track playing",
            style = MaterialTheme.typography.headlineSmallEmphasized,
            maxLines = 1,
            modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2_000),
        )
        Text(
            text = track?.artist ?: "Select a track from your library",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2_500),
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun UtilityActionButton(
    iconRes: Int,
    label: String,
    enabled: Boolean,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = when {
        !enabled -> Color.Transparent
        active -> MaterialTheme.colorScheme.secondaryContainer
        else -> Color.Transparent
    }
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        active -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .clip(MaterialTheme.shapes.large)
            .background(containerColor)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
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
            style = MaterialTheme.typography.labelSmallEmphasized,
            color = contentColor,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlaybackSeekbar(
    positionMs: Long,
    durationMs: Long,
    enabled: Boolean,
    onSeek: (Long) -> Unit,
    isPlaying: Boolean = false,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragPos by remember { mutableFloatStateOf(0f) }
    val safeDuration = durationMs.coerceAtLeast(1L)
    val displayedMs = if (dragging) dragPos.toLong() else positionMs.coerceIn(0L, safeDuration)
    val fraction = (displayedMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)

    val latestOnSeek by rememberUpdatedState(onSeek)
    var canvasWidthPx by remember { mutableFloatStateOf(1f) }

    val waveAmp by animateFloatAsState(
        targetValue = if (isPlaying && !dragging && enabled) 1f else 0f,
        animationSpec = LumynMotionTokens.effectsFast(),
        label = "seekbarWaveAmp",
    )
    val thumbHeight by animateDpAsState(
        targetValue = if (dragging) 24.dp else 18.dp,
        animationSpec = LumynMotionTokens.spatialFast(),
        label = "seekbarThumbHeight",
    )
    var wavePhase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(isPlaying, dragging, enabled) {
        if (!isPlaying || dragging || !enabled) return@LaunchedEffect
        var lastNanos = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dtSec = ((now - lastNanos).coerceIn(0L, 100_000_000L)) / 1_000_000_000f
            lastNanos = now
            wavePhase = (wavePhase + dtSec * 3.8f) % (2f * PI.toFloat())
        }
    }

    val activeColor = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)
    }
    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.24f)

    Column(modifier = Modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .onSizeChanged { canvasWidthPx = it.width.toFloat().coerceAtLeast(1f) }
                .semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        current = fraction,
                        range = 0f..1f,
                    )
                    if (enabled) {
                        setProgress { target ->
                            val clamped = target.coerceIn(0f, 1f)
                            latestOnSeek((clamped * safeDuration).toLong())
                            true
                        }
                    }
                }
                .pointerInput(enabled, safeDuration) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { offset ->
                        val pad = 8.dp.toPx()
                        val usable = (size.width.toFloat() - 2f * pad).coerceAtLeast(1f)
                        val f = ((offset.x - pad) / usable).coerceIn(0f, 1f)
                        latestOnSeek((f * safeDuration).toLong())
                    }
                }
                .pointerInput(enabled, safeDuration) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            val pad = 8.dp.toPx()
                            val usable = (size.width.toFloat() - 2f * pad).coerceAtLeast(1f)
                            val f = ((offset.x - pad) / usable).coerceIn(0f, 1f)
                            dragging = true
                            dragPos = f * safeDuration.toFloat()
                        },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            val pad = 8.dp.toPx()
                            val usable = (size.width.toFloat() - 2f * pad).coerceAtLeast(1f)
                            val f = ((change.position.x - pad) / usable).coerceIn(0f, 1f)
                            dragPos = f * safeDuration.toFloat()
                        },
                        onDragEnd = {
                            val targetMs = dragPos.toLong()
                            dragging = false
                            latestOnSeek(targetMs)
                        },
                        onDragCancel = {
                            dragging = false
                        },
                    )
                },
        ) {
            val pad = 8.dp.toPx()
            val usable = (canvasWidthPx - 2f * pad).coerceAtLeast(1f)
            val centerY = size.height / 2f
            val thumbX = pad + fraction * usable
            val gap = 7.dp.toPx()
            val trackStroke = 3.5.dp.toPx()

            val inactiveStart = (thumbX + gap).coerceAtMost(pad + usable)
            val inactiveEnd = pad + usable
            if (inactiveEnd - inactiveStart > 2f) {
                drawLine(
                    color = inactiveColor,
                    start = Offset(inactiveStart, centerY),
                    end = Offset(inactiveEnd, centerY),
                    strokeWidth = trackStroke,
                    cap = StrokeCap.Round,
                )
            }
            if (inactiveEnd - thumbX > 4.dp.toPx()) {
                drawCircle(
                    color = activeColor.copy(alpha = 0.75f),
                    radius = 2.dp.toPx(),
                    center = Offset(inactiveEnd, centerY),
                )
            }

            val activeEnd = (thumbX - gap).coerceAtLeast(pad)
            if (activeEnd - pad > 1f) {
                if (waveAmp > 0.02f) {
                    val amplitude = 2.8.dp.toPx() * waveAmp
                    val wavelength = 28.dp.toPx()
                    val twoPiOverLambda = (2f * PI.toFloat()) / wavelength
                    val path = Path()
                    var x = pad
                    path.moveTo(x, centerY + sin(x * twoPiOverLambda - wavePhase) * amplitude)
                    val step = 3f
                    while (x < activeEnd) {
                        x = (x + step).coerceAtMost(activeEnd)
                        val y = centerY + sin(x * twoPiOverLambda - wavePhase) * amplitude
                        path.lineTo(x, y)
                    }
                    drawPath(
                        path = path,
                        color = activeColor,
                        style = Stroke(width = trackStroke, cap = StrokeCap.Round),
                    )
                } else {
                    drawLine(
                        color = activeColor,
                        start = Offset(pad, centerY),
                        end = Offset(activeEnd, centerY),
                        strokeWidth = trackStroke,
                        cap = StrokeCap.Round,
                    )
                }
            }

            val thumbWidth = 4.dp.toPx()
            val thumbH = thumbHeight.toPx()
            drawRoundRect(
                color = activeColor,
                topLeft = Offset(thumbX - thumbWidth / 2f, centerY - thumbH / 2f),
                size = Size(thumbWidth, thumbH),
                cornerRadius = CornerRadius(thumbWidth / 2f, thumbWidth / 2f),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatDurationMs(displayedMs),
                style = MaterialTheme.typography.labelSmallEmphasized,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatDurationMs(durationMs),
                style = MaterialTheme.typography.labelSmallEmphasized,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ShuffleButton(
    enabled: Boolean,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    FilledTonalIconToggleButton(
        checked = checked,
        onCheckedChange = { onToggle() },
        enabled = enabled,
        shapes = IconButtonDefaults.toggleableShapes(),
        colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        modifier = Modifier
            .size(44.dp)
            .semantics {
                stateDescription = if (checked) "Shuffle on" else "Shuffle off"
            },
    ) {
        Icon(
            painter = painterResource(LumynIcons.Shuffle),
            contentDescription = "Toggle shuffle",
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun RepeatButton(
    enabled: Boolean,
    repeatMode: RepeatMode,
    onCycle: () -> Unit,
) {
    val active = repeatMode != RepeatMode.OFF
    FilledTonalIconToggleButton(
        checked = active,
        onCheckedChange = { onCycle() },
        enabled = enabled,
        shapes = IconButtonDefaults.toggleableShapes(),
        colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        modifier = Modifier
            .size(44.dp)
            .semantics {
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
