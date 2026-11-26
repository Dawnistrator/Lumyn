package com.lumyn.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumyn.core.designsystem.component.LumynArtwork
import com.lumyn.core.designsystem.component.LumynListDivider
import com.lumyn.core.designsystem.component.LumynListGroup
import com.lumyn.core.designsystem.component.LumynSectionHeader
import com.lumyn.core.designsystem.component.TrackListItem
import com.lumyn.core.designsystem.component.lumynGroupedListItemColors
import com.lumyn.core.designsystem.icon.LumynIcons
import com.lumyn.core.model.ListeningStats
import com.lumyn.core.model.Track

@Composable
fun ListeningStatsEntry(
    currentTrackId: Long?,
    onNavigateBack: () -> Unit,
    onOpenArtist: (String) -> Unit,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onPlayNext: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onEditTrackTags: (Track) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ListeningStatsScreen(
        stats = state.listeningStats,
        currentTrackId = currentTrackId,
        onNavigateBack = onNavigateBack,
        onOpenArtist = onOpenArtist,
        onPlayTracks = onPlayTracks,
        onPlayNext = onPlayNext,
        onAddToQueue = onAddToQueue,
        onAddToPlaylist = onAddToPlaylist,
        onToggleFavorite = viewModel::toggleFavorite,
        onEditTrackTags = onEditTrackTags,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListeningStatsScreen(
    stats: ListeningStats,
    currentTrackId: Long?,
    onNavigateBack: () -> Unit,
    onOpenArtist: (String) -> Unit = {},
    onPlayTracks: (List<Track>, Int) -> Unit = { _, _ -> },
    onPlayNext: (Track) -> Unit = {},
    onAddToQueue: (Track) -> Unit = {},
    onAddToPlaylist: (Track) -> Unit = {},
    onToggleFavorite: (Track) -> Unit = {},
    onEditTrackTags: (Track) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Listening stats") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            painter = painterResource(LumynIcons.ArrowBack),
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding()),
            contentPadding = PaddingValues(bottom = innerPadding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "stats-grid", contentType = "metrics") {
                val hiResPct = if (stats.totalPlays > 0) {
                    (stats.hiResPlays * 100) / stats.totalPlays
                } else {
                    0
                }
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MetricCard(
                            label = "Total plays",
                            value = stats.totalPlays.toString(),
                            modifier = Modifier.weight(1f),
                        )
                        MetricCard(
                            label = "Listening time",
                            value = formatListeningHoursMinutes(stats.totalListeningMs),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MetricCard(
                            label = "Tracks played",
                            value = stats.uniqueTracksPlayed.toString(),
                            modifier = Modifier.weight(1f),
                        )
                        MetricCard(
                            label = "Hi-Res share",
                            value = "$hiResPct%",
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            if (stats.totalPlays == 0) {
                item(key = "stats-empty", contentType = "empty") {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("No local listening history yet", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "Listen to tracks in Lumyn and your 100% offline listening insights " +
                                    "will build up automatically.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                if (stats.topArtists.isNotEmpty()) {
                    item(key = "top-artists-header", contentType = "section-header") {
                        LumynSectionHeader("Top artists")
                    }
                    item(key = "top-artists-group", contentType = "artists") {
                        val maxArtistPlays = stats.topArtists.maxOfOrNull { it.playCount }?.coerceAtLeast(1) ?: 1
                        LumynListGroup(modifier = Modifier.padding(horizontal = 16.dp)) {
                            stats.topArtists.forEachIndexed { index, artist ->
                                ListItem(
                                    modifier = Modifier.clickable { onOpenArtist(artist.artistName) },
                                    colors = lumynGroupedListItemColors(),
                                    leadingContent = {
                                        LumynArtwork(
                                            artworkUri = artist.artworkUri,
                                            contentDescription = null,
                                            cornerRadius = 20.dp,
                                            fallbackIcon = LumynIcons.Person,
                                            modifier = Modifier.size(44.dp),
                                        )
                                    },
                                    headlineContent = {
                                        Text(
                                            text = "${index + 1}. ${artist.artistName}",
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    supportingContent = {
                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            val artistPlaysLabel = if (artist.playCount == 1) {
                                                "1 play"
                                            } else {
                                                "${artist.playCount} plays"
                                            }
                                            Text(
                                                text = "$artistPlaysLabel • " +
                                                    formatListeningHoursMinutes(artist.totalListeningMs),
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                            LinearProgressIndicator(
                                                progress = { (artist.playCount.toFloat() / maxArtistPlays).coerceIn(0f, 1f) },
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                        }
                                    },
                                )
                                if (index < stats.topArtists.lastIndex) {
                                    LumynListDivider()
                                }
                            }
                        }
                    }
                }

                if (stats.topTracks.isNotEmpty()) {
                    item(key = "top-tracks-header", contentType = "section-header") {
                        LumynSectionHeader("Most played songs")
                    }
                    itemsIndexed(
                        items = stats.topTracks,
                        key = { _, track -> track.id },
                        contentType = { _, _ -> "track" },
                    ) { index, track ->
                        TrackListItem(
                            track = track,
                            isPlaying = track.id == currentTrackId,
                            onClick = { onPlayTracks(stats.topTracks, index) },
                            onToggleFavorite = { onToggleFavorite(track) },
                            onPlayNext = { onPlayNext(track) },
                            onAddToQueue = { onAddToQueue(track) },
                            onAddToPlaylist = { onAddToPlaylist(track) },
                            onEditTags = { onEditTrackTags(track) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

internal fun formatListeningHoursMinutes(durationMs: Long): String {
    val totalMinutes = (durationMs / 60_000L).coerceAtLeast(0L)
    val hours = totalMinutes / 60L
    val mins = totalMinutes % 60L
    return when {
        hours > 0L -> "${hours}h ${mins}m"
        totalMinutes > 0L -> "${mins}m"
        durationMs >= 1_000L -> "${durationMs / 1_000L}s"
        else -> "0m"
    }
}
