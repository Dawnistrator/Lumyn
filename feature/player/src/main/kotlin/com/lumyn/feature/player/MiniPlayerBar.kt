package com.lumyn.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lumyn.core.designsystem.component.LumynArtwork
import com.lumyn.core.designsystem.icon.LumynIcons
import com.lumyn.core.designsystem.theme.nestedCornerRadius
import com.lumyn.core.model.PlaybackState

/**
 * Persistent mini-player. Sits above the navigation bar on phones and at the bottom of the
 * content area on medium windows.
 */
@Composable
fun MiniPlayerBar(
    state: PlaybackState,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onOpenNowPlaying: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = state.currentTrack
    val hasTrack = track != null
    Surface(
        onClick = onOpenNowPlaying,
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LumynArtwork(
                    artworkUri = track?.artworkUri,
                    contentDescription = null,
                    cornerRadius = nestedCornerRadius(outerRadius = 16.dp, padding = 8.dp),
                    modifier = Modifier.size(48.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track?.title ?: "Ready to play local music",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = track?.let { "${it.artist} • ${it.audioSpec.formatQualitySummary()}" }
                            ?: "Select a track to start",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                FilledIconButton(onClick = onPlayPause, enabled = hasTrack) {
                    Icon(
                        painter = painterResource(if (state.isPlaying) LumynIcons.Pause else LumynIcons.PlayArrow),
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                    )
                }
                IconButton(onClick = onNext, enabled = state.hasNext) {
                    Icon(
                        painter = painterResource(LumynIcons.SkipNext),
                        contentDescription = "Next track",
                    )
                }
            }
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
