package com.lumyn.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.carousel.HorizontalCenteredHeroCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumyn.core.designsystem.component.LumynArtwork
import com.lumyn.core.designsystem.component.LumynListDivider
import com.lumyn.core.designsystem.component.LumynListGroup
import com.lumyn.core.designsystem.component.LumynSectionHeader
import com.lumyn.core.designsystem.component.LumynStatusRow
import com.lumyn.core.designsystem.component.TrackListItem
import com.lumyn.core.designsystem.icon.LumynIcons
import com.lumyn.core.mediascanner.scanner.ScanStatus
import com.lumyn.core.model.AlbumSummary
import com.lumyn.core.model.AudioCapabilitiesSnapshot
import com.lumyn.core.model.AudioPermissionStatus
import com.lumyn.core.model.ListeningStats
import com.lumyn.core.model.SmartPlaylistSummary
import com.lumyn.core.model.SmartPlaylistType
import com.lumyn.core.model.Track

@Composable
fun HomeEntry(
    audioPermission: AudioPermissionStatus,
    currentTrackId: Long?,
    onPermissionAction: () -> Unit,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onPlayNext: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onAddToPlaylist: (Track) -> Unit = {},
    onEditTrackTags: (Track) -> Unit = {},
    onOpenAlbum: (Long) -> Unit = {},
    onOpenSmartPlaylist: (SmartPlaylistType) -> Unit = {},
    onOpenStats: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HomeScreen(
        audioPermission = audioPermission,
        uiState = state,
        currentTrackId = currentTrackId,
        onPermissionAction = onPermissionAction,
        onScanClick = viewModel::triggerScan,
        onPlayTracks = onPlayTracks,
        onPlayNext = onPlayNext,
        onAddToQueue = onAddToQueue,
        onAddToPlaylist = onAddToPlaylist,
        onToggleFavorite = viewModel::toggleFavorite,
        onEditTrackTags = onEditTrackTags,
        onOpenAlbum = onOpenAlbum,
        onOpenSmartPlaylist = onOpenSmartPlaylist,
        onOpenStats = onOpenStats,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    audioPermission: AudioPermissionStatus,
    uiState: HomeUiState,
    currentTrackId: Long?,
    onPermissionAction: () -> Unit,
    onScanClick: () -> Unit,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onPlayNext: (Track) -> Unit = {},
    onAddToQueue: (Track) -> Unit = {},
    onAddToPlaylist: (Track) -> Unit = {},
    onToggleFavorite: (Track) -> Unit,
    onEditTrackTags: (Track) -> Unit = {},
    onOpenAlbum: (Long) -> Unit = {},
    onOpenSmartPlaylist: (SmartPlaylistType) -> Unit = {},
    onOpenStats: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val layoutDirection = LocalLayoutDirection.current

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Lumyn") },
                actions = {
                    if (audioPermission == AudioPermissionStatus.Granted) {
                        IconButton(onClick = onScanClick) {
                            Icon(
                                painter = painterResource(LumynIcons.Refresh),
                                contentDescription = "Rescan library",
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = innerPadding.calculateStartPadding(layoutDirection),
                end = innerPadding.calculateEndPadding(layoutDirection),
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (uiState.scanStatus is ScanStatus.Scanning) {
                item(key = "scan-progress", contentType = "scan-progress") {
                    ScanProgressBanner(
                        status = uiState.scanStatus,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            if (audioPermission != AudioPermissionStatus.Granted) {
                item(key = "permission", contentType = "permission") {
                    PermissionBanner(
                        status = audioPermission,
                        onAction = onPermissionAction,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            item(key = "summary", contentType = "summary") {
                OfflineSummary(
                    totalTracks = uiState.totalTrackCount,
                    hiResTracks = uiState.hiResTrackCount,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            if (uiState.totalTrackCount > 0 && uiState.smartPlaylists.isNotEmpty()) {
                item(key = "smart-header", contentType = "section-header") {
                    LumynSectionHeader("Smart playlists")
                }
                item(key = "smart-row", contentType = "smart-row") {
                    SmartPlaylistsRow(
                        smartPlaylists = uiState.smartPlaylists,
                        onOpenSmartPlaylist = onOpenSmartPlaylist,
                    )
                }
            }

            if (uiState.totalTrackCount > 0) {
                item(key = "insights-card", contentType = "insights") {
                    ListeningInsightsCard(
                        stats = uiState.listeningStats,
                        onOpenStats = onOpenStats,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            if (uiState.spotlightAlbums.isNotEmpty()) {
                item(key = "spotlight-header", contentType = "section-header") {
                    LumynSectionHeader("Album spotlight")
                }
                item(key = "spotlight-carousel", contentType = "carousel") {
                    AlbumSpotlightCarousel(
                        albums = uiState.spotlightAlbums,
                        onOpenAlbum = onOpenAlbum,
                    )
                }
            }

            if (uiState.recentTracks.isNotEmpty()) {
                item(key = "recent-header", contentType = "section-header") {
                    LumynSectionHeader("Recently added")
                }
                itemsIndexed(
                    items = uiState.recentTracks,
                    key = { _, track -> track.id },
                    contentType = { _, _ -> "track" },
                ) { index, track ->
                    TrackListItem(
                        track = track,
                        isPlaying = track.id == currentTrackId,
                        onClick = { onPlayTracks(uiState.recentTracks, index) },
                        onToggleFavorite = { onToggleFavorite(track) },
                        onPlayNext = { onPlayNext(track) },
                        onAddToQueue = { onAddToQueue(track) },
                        onAddToPlaylist = { onAddToPlaylist(track) },
                        onEditTags = { onEditTrackTags(track) },
                    )
                }
            } else if (audioPermission == AudioPermissionStatus.Granted &&
                uiState.scanStatus !is ScanStatus.Scanning
            ) {
                item(key = "empty-library", contentType = "empty") {
                    EmptyLibraryCard(
                        onScanClick = onScanClick,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            item(key = "capabilities", contentType = "capabilities") {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    LumynSectionHeader("Hi-Res audio engine")
                    CapabilityGroup(uiState.capabilities)
                }
            }
        }
    }
}

@Composable
private fun SmartPlaylistsRow(
    smartPlaylists: List<SmartPlaylistSummary>,
    onOpenSmartPlaylist: (SmartPlaylistType) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items = smartPlaylists, key = { it.type.name }) { item ->
            val iconRes = when (item.type) {
                SmartPlaylistType.RECENTLY_PLAYED -> LumynIcons.History
                SmartPlaylistType.MOST_PLAYED -> LumynIcons.BarChart
                SmartPlaylistType.FORGOTTEN_GEMS -> LumynIcons.Star
                SmartPlaylistType.HI_RES_COLLECTION -> LumynIcons.GraphicEq
                SmartPlaylistType.FAVORITES -> LumynIcons.FavoriteFilled
            }
            Surface(
                onClick = { onOpenSmartPlaylist(item.type) },
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.width(156.dp),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        painter = painterResource(iconRes),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${item.trackCount} ${if (item.trackCount == 1) "track" else "tracks"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ListeningInsightsCard(
    stats: ListeningStats,
    onOpenStats: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onOpenStats,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                painter = painterResource(LumynIcons.BarChart),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Listening insights", style = MaterialTheme.typography.titleSmall)
                val summary = if (stats.totalPlays == 0) {
                    "Play music to collect 100% offline listening stats"
                } else {
                    val hiResPct = (stats.hiResPlays * 100) / stats.totalPlays
                    val playsLabel = if (stats.totalPlays == 1) "1 play" else "${stats.totalPlays} plays"
                    "$playsLabel • ${formatListeningHoursMinutes(stats.totalListeningMs)} • $hiResPct% Hi-Res"
                }
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onOpenStats) { Text("View") }
        }
    }
}

@Composable
private fun ScanProgressBanner(status: ScanStatus.Scanning, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val label = if (status.totalCount > 0) {
                "Scanning local music: ${status.indexedCount} / ${status.totalCount} files"
            } else {
                "Inspecting audio storage…"
            }
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            if (status.totalCount > 0) {
                LinearProgressIndicator(
                    progress = { (status.indexedCount.toFloat() / status.totalCount).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun PermissionBanner(
    status: AudioPermissionStatus,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val permanent = status == AudioPermissionStatus.PermanentlyDenied
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Allow audio file access", style = MaterialTheme.typography.titleMedium)
            Text(
                text = if (permanent) {
                    "Permission denied. Open app settings to allow Lumyn to read local music on this device."
                } else {
                    "Lumyn only reads music stored on your device. No data is ever sent anywhere."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onAction) {
                Text(if (permanent) "Open settings" else "Allow audio access")
            }
        }
    }
}

@Composable
private fun OfflineSummary(
    totalTracks: Int,
    hiResTracks: Int,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Offline Hi-Res music player", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "FLAC, WAV, and other lossless formats played from local storage, " +
                    "without accounts or internet access.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ) {
                    Text(
                        text = "100% offline",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                if (totalTracks > 0) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ) {
                        val songsLabel = if (totalTracks == 1) "1 song" else "$totalTracks songs"
                        Text(
                            text = "$songsLabel • $hiResTracks Hi-Res",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlbumSpotlightCarousel(
    albums: List<AlbumSummary>,
    onOpenAlbum: (Long) -> Unit,
) {
    key(albums.size) {
        val state = rememberCarouselState { albums.size }
        HorizontalCenteredHeroCarousel(
            state = state,
            modifier = Modifier
                .fillMaxWidth()
                .height(210.dp),
            itemSpacing = 8.dp,
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) { index ->
            val album = albums[index]
            val scrimBottom = MaterialTheme.colorScheme.scrim.copy(alpha = 0.72f)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .maskClip(MaterialTheme.shapes.extraLarge)
                    .clickable { onOpenAlbum(album.id) },
            ) {
                LumynArtwork(
                    artworkUri = album.artworkUri,
                    contentDescription = album.title,
                    cornerRadius = 0.dp,
                    fallbackIcon = LumynIcons.Album,
                    fallbackIconSize = 48.dp,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomStart)
                        .background(
                            Brush.verticalGradient(listOf(Color.Transparent, scrimBottom)),
                        )
                        .padding(16.dp),
                ) {
                    Column {
                        Text(
                            text = album.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val countLabel = if (album.trackCount == 1) "1 song" else "${album.trackCount} songs"
                        Text(
                            text = "${album.artist} • $countLabel",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyLibraryCard(onScanClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("No tracks indexed yet", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Scan local storage or check folder filters in Settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onScanClick) {
                Text("Scan local music")
            }
        }
    }
}

@Composable
private fun CapabilityGroup(capabilities: AudioCapabilitiesSnapshot) {
    LumynListGroup {
        LumynStatusRow(
            title = "FFmpeg / FLAC extension decoder",
            supporting = "FLAC support on API 26 plus ALAC, APE, WavPack & WMA",
            statusText = if (capabilities.hasFfmpegExtension) "Active" else "Platform",
        )
        LumynListDivider()
        LumynStatusRow(
            title = "32-bit float output",
            supporting = "Preserves headroom while EQ and ReplayGain are active",
            statusText = if (capabilities.supportsFloatOutput) "Active" else "Unavailable",
        )
        LumynListDivider()
        LumynStatusRow(
            title = "Direct playback",
            supporting = "Bypasses the system mixer (Android 13+)",
            statusText = if (capabilities.supportsDirectPlayback) "Supported" else "Unsupported",
        )
        LumynListDivider()
        LumynStatusRow(
            title = "Bit-perfect USB DAC",
            supporting = "Native sample rate & bit depth to DAC (Android 14+)",
            statusText = if (capabilities.supportsBitPerfectUsb) "Supported" else "Unsupported",
        )
        LumynListDivider()
        LumynStatusRow(
            title = "Current output",
            supporting = capabilities.activeOutputSummary,
            statusText = "API ${capabilities.apiLevel}",
        )
    }
}
