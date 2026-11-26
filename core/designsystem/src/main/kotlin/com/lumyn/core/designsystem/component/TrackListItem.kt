package com.lumyn.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lumyn.core.designsystem.icon.LumynIcons
import com.lumyn.core.model.Track

/**
 * Standard track row placed directly on `surface` (never wrapped in a Card).
 * Uses `secondaryContainer` + bold title + EQ icon together when [isPlaying] is true.
 */
@Composable
fun TrackListItem(
    track: Track,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
    onPlayNext: (() -> Unit)? = null,
    onAddToQueue: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    onEditTags: (() -> Unit)? = null,
) {
    val containerColor = if (isPlaying) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    ListItem(
        modifier = modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = containerColor),
        leadingContent = {
            LumynArtwork(
                artworkUri = track.artworkUri,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                cornerRadius = 8.dp,
            )
        },
        headlineContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (isPlaying) {
                    Icon(
                        painter = painterResource(LumynIcons.GraphicEq),
                        contentDescription = "Now playing",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Text(
                    text = track.title,
                    fontWeight = if (isPlaying) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "${track.artist} • ${track.album}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (track.audioSpec.isHiRes) {
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        ) {
                            Text(
                                text = "Hi-Res",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            )
                        }
                    }
                    Text(
                        text = track.audioSpec.formatQualitySummary(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatDurationMs(track.durationMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconToggleButton(
                    checked = track.isFavorite,
                    onCheckedChange = { onToggleFavorite() },
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
                if (onPlayNext != null ||
                    onAddToQueue != null ||
                    onAddToPlaylist != null ||
                    onRemoveFromPlaylist != null ||
                    onEditTags != null
                ) {
                    TrackOverflowButton(
                        onPlayNext = onPlayNext,
                        onAddToQueue = onAddToQueue,
                        onAddToPlaylist = onAddToPlaylist,
                        onRemoveFromPlaylist = onRemoveFromPlaylist,
                        onEditTags = onEditTags,
                    )
                }
            }
        },
    )
}

@Composable
private fun TrackOverflowButton(
    onPlayNext: (() -> Unit)?,
    onAddToQueue: (() -> Unit)?,
    onAddToPlaylist: (() -> Unit)?,
    onRemoveFromPlaylist: (() -> Unit)?,
    onEditTags: (() -> Unit)?,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                painter = painterResource(LumynIcons.MoreVert),
                contentDescription = "Track options",
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (onPlayNext != null) {
                DropdownMenuItem(
                    text = { Text("Play next") },
                    leadingIcon = {
                        Icon(painter = painterResource(LumynIcons.PlaylistPlay), contentDescription = null)
                    },
                    onClick = {
                        expanded = false
                        onPlayNext()
                    },
                )
            }
            if (onAddToQueue != null) {
                DropdownMenuItem(
                    text = { Text("Add to queue") },
                    leadingIcon = {
                        Icon(painter = painterResource(LumynIcons.PlaylistAdd), contentDescription = null)
                    },
                    onClick = {
                        expanded = false
                        onAddToQueue()
                    },
                )
            }
            if (onAddToPlaylist != null) {
                DropdownMenuItem(
                    text = { Text("Add to playlist") },
                    leadingIcon = {
                        Icon(painter = painterResource(LumynIcons.QueueMusic), contentDescription = null)
                    },
                    onClick = {
                        expanded = false
                        onAddToPlaylist()
                    },
                )
            }
            if (onRemoveFromPlaylist != null) {
                DropdownMenuItem(
                    text = { Text("Remove from playlist") },
                    leadingIcon = {
                        Icon(painter = painterResource(LumynIcons.PlaylistRemove), contentDescription = null)
                    },
                    onClick = {
                        expanded = false
                        onRemoveFromPlaylist()
                    },
                )
            }
            if (onEditTags != null) {
                DropdownMenuItem(
                    text = { Text("Edit tags") },
                    leadingIcon = {
                        Icon(painter = painterResource(LumynIcons.Edit), contentDescription = null)
                    },
                    onClick = {
                        expanded = false
                        onEditTags()
                    },
                )
            }
        }
    }
}

fun formatDurationMs(durationMs: Long): String {
    val totalSeconds = (durationMs / 1_000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
