package com.lunaria.feature.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lunaria.core.designsystem.component.LunariaArtwork
import com.lunaria.core.designsystem.icon.LunariaIcons
import com.lunaria.core.designsystem.theme.LunariaMotionTokens
import com.lunaria.core.designsystem.theme.nestedCornerRadius
import com.lunaria.core.model.PlaybackState

/**
 * Floating pill mini-player themed to the active track's album art when available.
 * Hidden cleanly whenever no track is loaded in the queue.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MiniPlayerBar(
    state: PlaybackState,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onOpenNowPlaying: () -> Unit,
    modifier: Modifier = Modifier,
    useAlbumArtTheme: Boolean = true,
    darkTheme: Boolean = isSystemInDarkTheme(),
) {
    val track = state.currentTrack ?: return
    val playCorner by animateDpAsState(
        targetValue = if (state.isPlaying) 12.dp else 20.dp,
        animationSpec = LunariaMotionTokens.spatialFast(),
        label = "miniPlayerPlayCorner",
    )

    AnimatedAlbumArtTheme(
        track = track,
        enabled = useAlbumArtTheme,
        darkTheme = darkTheme,
    ) {
        Surface(
            onClick = onOpenNowPlaying,
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 6.dp,
            tonalElevation = 3.dp,
        ) {
            Column {
                Row(
                    modifier = Modifier.padding(start = 10.dp, end = 8.dp, top = 8.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    LunariaArtwork(
                        artworkUri = track.artworkUri,
                        contentDescription = null,
                        cornerRadius = nestedCornerRadius(outerRadius = 28.dp, padding = 10.dp),
                        modifier = Modifier.size(44.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.title,
                            style = MaterialTheme.typography.titleSmallEmphasized,
                            maxLines = 1,
                            modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2_000),
                        )
                        Text(
                            text = "${track.artist} • ${track.audioSpec.formatQualitySummary()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    FilledIconButton(
                        onClick = onPlayPause,
                        shape = RoundedCornerShape(playCorner),
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            painter = painterResource(
                                if (state.isPlaying) LunariaIcons.Pause else LunariaIcons.PlayArrow,
                            ),
                            contentDescription = if (state.isPlaying) "Pause" else "Play",
                        )
                    }
                    IconButton(
                        onClick = onNext,
                        enabled = state.hasNext,
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        Icon(
                            painter = painterResource(LunariaIcons.SkipNext),
                            contentDescription = "Next track",
                        )
                    }
                }
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, bottom = 4.dp)
                        .height(3.dp)
                        .clip(CircleShape),
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            }
        }
    }
}
