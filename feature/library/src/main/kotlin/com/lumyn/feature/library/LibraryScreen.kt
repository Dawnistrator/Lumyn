package com.lumyn.feature.library

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumyn.core.designsystem.component.LumynArtwork
import com.lumyn.core.designsystem.component.LumynSectionHeader
import com.lumyn.core.designsystem.component.TrackListItem
import com.lumyn.core.designsystem.component.formatDurationMs
import com.lumyn.core.designsystem.icon.LumynIcons
import com.lumyn.core.mediascanner.scanner.ScanStatus
import com.lumyn.core.model.AlbumSummary
import com.lumyn.core.model.ArtistSummary
import com.lumyn.core.model.AudioPermissionStatus
import com.lumyn.core.model.FolderSummary
import com.lumyn.core.model.PlaylistSummary
import com.lumyn.core.model.SmartPlaylistSummary
import com.lumyn.core.model.SmartPlaylistType
import com.lumyn.core.model.Track
import com.lumyn.core.model.TrackSortOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val LibraryTabs = listOf("Songs", "Albums", "Artists", "Folders", "Playlists")

@Composable
fun LibraryEntry(
    audioPermission: AudioPermissionStatus,
    currentTrackId: Long?,
    onPermissionAction: () -> Unit,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onPlayNext: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onAddToPlaylist: (Track) -> Unit = {},
    onEditTrackTags: (Track) -> Unit = {},
    onOpenAlbum: (Long) -> Unit = {},
    onOpenArtist: (String) -> Unit = {},
    onOpenFolder: (String) -> Unit = {},
    onOpenPlaylist: (Long) -> Unit = {},
    onOpenSmartPlaylist: (SmartPlaylistType) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingExportPlaylist by remember { mutableStateOf<PlaylistSummary?>(null) }

    val importM3uLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val loaded = withContext(Dispatchers.IO) { readUriTextAndName(context, uri) }
                if (loaded != null) viewModel.importM3uPlaylist(loaded.first, loaded.second)
            }
        }
    }

    val importCueLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val loaded = withContext(Dispatchers.IO) { readUriTextAndName(context, uri) }
                if (loaded != null) viewModel.importCueSheet(loaded.first, loaded.second)
            }
        }
    }

    val exportM3uLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/x-mpegurl"),
    ) { uri ->
        val target = pendingExportPlaylist
        pendingExportPlaylist = null
        if (uri != null && target != null) {
            scope.launch {
                val content = viewModel.exportPlaylistM3u8(target.id)
                val wrote = withContext(Dispatchers.IO) { writeTextToUri(context, uri, content) }
                if (wrote) viewModel.notifyExportSuccess(target.name)
            }
        }
    }

    LibraryScreen(
        audioPermission = audioPermission,
        uiState = uiState,
        currentTrackId = currentTrackId,
        onPermissionAction = onPermissionAction,
        onScanClick = viewModel::triggerScan,
        onSortOrderChange = viewModel::setSortOrder,
        onPlayTracks = onPlayTracks,
        onPlayNext = onPlayNext,
        onAddToQueue = onAddToQueue,
        onAddToPlaylist = onAddToPlaylist,
        onToggleFavorite = viewModel::toggleFavorite,
        onEditTrackTags = onEditTrackTags,
        onOpenAlbum = onOpenAlbum,
        onOpenArtist = onOpenArtist,
        onOpenFolder = onOpenFolder,
        onOpenPlaylist = onOpenPlaylist,
        onOpenSmartPlaylist = onOpenSmartPlaylist,
        onCreatePlaylist = viewModel::createPlaylist,
        onDeletePlaylist = viewModel::deletePlaylist,
        onImportM3uClick = {
            importM3uLauncher.launch(arrayOf("audio/x-mpegurl", "audio/mpegurl", "text/*", "*/*"))
        },
        onImportCueClick = {
            importCueLauncher.launch(arrayOf("application/x-cue", "text/*", "*/*"))
        },
        onExportPlaylistClick = { playlist ->
            pendingExportPlaylist = playlist
            exportM3uLauncher.launch("${playlist.name}.m3u8")
        },
        onDismissNotice = viewModel::clearPlaylistNotice,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LibraryScreen(
    audioPermission: AudioPermissionStatus,
    uiState: LibraryUiState,
    currentTrackId: Long?,
    onPermissionAction: () -> Unit,
    onScanClick: () -> Unit,
    onSortOrderChange: (TrackSortOrder) -> Unit,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onPlayNext: (Track) -> Unit = {},
    onAddToQueue: (Track) -> Unit = {},
    onAddToPlaylist: (Track) -> Unit = {},
    onToggleFavorite: (Track) -> Unit,
    onEditTrackTags: (Track) -> Unit = {},
    onOpenAlbum: (Long) -> Unit = {},
    onOpenArtist: (String) -> Unit = {},
    onOpenFolder: (String) -> Unit = {},
    onOpenPlaylist: (Long) -> Unit = {},
    onOpenSmartPlaylist: (SmartPlaylistType) -> Unit = {},
    onCreatePlaylist: (String) -> Unit,
    onDeletePlaylist: (Long) -> Unit,
    onImportM3uClick: () -> Unit = {},
    onImportCueClick: () -> Unit = {},
    onExportPlaylistClick: (PlaylistSummary) -> Unit = {},
    onDismissNotice: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var showCreatePlaylistDialog by rememberSaveable { mutableStateOf(false) }
    var pendingDeletePlaylist by remember { mutableStateOf<PlaylistSummary?>(null) }

    if (showCreatePlaylistDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreatePlaylistDialog = false },
            onConfirm = { name ->
                onCreatePlaylist(name)
                showCreatePlaylistDialog = false
            },
        )
    }

    pendingDeletePlaylist?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDeletePlaylist = null },
            title = { Text("Delete playlist?") },
            text = { Text("Playlist \"${target.name}\" will be removed from your local library.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeletePlaylist(target.id)
                        pendingDeletePlaylist = null
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeletePlaylist = null }) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Library") },
                actions = {
                    if (selectedTab == 0) {
                        Box {
                            IconButton(onClick = { sortMenuExpanded = true }) {
                                Icon(
                                    painter = painterResource(LumynIcons.Sort),
                                    contentDescription = "Sort songs",
                                )
                            }
                            DropdownMenu(
                                expanded = sortMenuExpanded,
                                onDismissRequest = { sortMenuExpanded = false },
                            ) {
                                SortMenuEntries(
                                    current = uiState.sortOrder,
                                    onSelect = { order ->
                                        onSortOrderChange(order)
                                        sortMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                    if (audioPermission == AudioPermissionStatus.Granted) {
                        IconButton(
                            onClick = onScanClick,
                            shapes = IconButtonDefaults.shapes(),
                        ) {
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
        Column(modifier = Modifier.fillMaxSize().padding(top = innerPadding.calculateTopPadding())) {
            if (uiState.scanStatus is ScanStatus.Scanning) {
                LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            PrimaryScrollableTabRow(selectedTabIndex = selectedTab, edgePadding = 16.dp) {
                LibraryTabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title, maxLines = 1) },
                    )
                }
            }

            val bottomInset = innerPadding.calculateBottomPadding()
            when (selectedTab) {
                0 -> TracksTab(
                    tracks = uiState.tracks,
                    currentTrackId = currentTrackId,
                    audioPermission = audioPermission,
                    bottomInset = bottomInset,
                    onPermissionAction = onPermissionAction,
                    onScanClick = onScanClick,
                    onPlayTracks = onPlayTracks,
                    onPlayNext = onPlayNext,
                    onAddToQueue = onAddToQueue,
                    onAddToPlaylist = onAddToPlaylist,
                    onToggleFavorite = onToggleFavorite,
                    onEditTrackTags = onEditTrackTags,
                    modifier = Modifier.weight(1f),
                )

                1 -> AlbumsTab(
                    albums = uiState.albums,
                    audioPermission = audioPermission,
                    bottomInset = bottomInset,
                    onPermissionAction = onPermissionAction,
                    onScanClick = onScanClick,
                    onOpenAlbum = onOpenAlbum,
                    modifier = Modifier.weight(1f),
                )

                2 -> ArtistsTab(
                    artists = uiState.artists,
                    audioPermission = audioPermission,
                    bottomInset = bottomInset,
                    onPermissionAction = onPermissionAction,
                    onScanClick = onScanClick,
                    onOpenArtist = onOpenArtist,
                    modifier = Modifier.weight(1f),
                )

                3 -> FoldersTab(
                    folders = uiState.folders,
                    audioPermission = audioPermission,
                    bottomInset = bottomInset,
                    onPermissionAction = onPermissionAction,
                    onScanClick = onScanClick,
                    onOpenFolder = onOpenFolder,
                    modifier = Modifier.weight(1f),
                )

                4 -> PlaylistsTab(
                    smartPlaylists = uiState.smartPlaylists,
                    playlists = uiState.playlists,
                    notice = uiState.playlistNotice,
                    bottomInset = bottomInset,
                    onCreateClick = { showCreatePlaylistDialog = true },
                    onImportM3uClick = onImportM3uClick,
                    onImportCueClick = onImportCueClick,
                    onOpenSmartPlaylist = onOpenSmartPlaylist,
                    onOpenPlaylist = { onOpenPlaylist(it.id) },
                    onExportClick = onExportPlaylistClick,
                    onDeleteClick = { pendingDeletePlaylist = it },
                    onDismissNotice = onDismissNotice,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SortMenuEntries(
    current: TrackSortOrder,
    onSelect: (TrackSortOrder) -> Unit,
) {
    val labels = listOf(
        TrackSortOrder.TITLE_ASC to "Title (A–Z)",
        TrackSortOrder.ARTIST_ASC to "Artist",
        TrackSortOrder.ALBUM_ASC to "Album",
        TrackSortOrder.DATE_ADDED_DESC to "Recently added",
        TrackSortOrder.DURATION_DESC to "Longest duration",
    )
    labels.forEach { (order, label) ->
        DropdownMenuItem(
            text = { Text(label) },
            trailingIcon = if (order == current) {
                { Icon(painter = painterResource(LumynIcons.Check), contentDescription = null) }
            } else {
                null
            },
            onClick = { onSelect(order) },
        )
    }
}

@Composable
private fun TracksTab(
    tracks: List<Track>,
    currentTrackId: Long?,
    audioPermission: AudioPermissionStatus,
    bottomInset: androidx.compose.ui.unit.Dp,
    onPermissionAction: () -> Unit,
    onScanClick: () -> Unit,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onPlayNext: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onToggleFavorite: (Track) -> Unit,
    onEditTrackTags: (Track) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tracks.isEmpty()) {
        LibraryEmptyState("songs", audioPermission, onPermissionAction, onScanClick, modifier)
        return
    }
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = bottomInset + 16.dp),
    ) {
        itemsIndexed(items = tracks, key = { _, track -> track.id }, contentType = { _, _ -> "track" }) { index, track ->
            TrackListItem(
                track = track,
                isPlaying = track.id == currentTrackId,
                onClick = { onPlayTracks(tracks, index) },
                onToggleFavorite = { onToggleFavorite(track) },
                onPlayNext = { onPlayNext(track) },
                onAddToQueue = { onAddToQueue(track) },
                onAddToPlaylist = { onAddToPlaylist(track) },
                onEditTags = { onEditTrackTags(track) },
            )
        }
    }
}

@Composable
private fun AlbumsTab(
    albums: List<AlbumSummary>,
    audioPermission: AudioPermissionStatus,
    bottomInset: androidx.compose.ui.unit.Dp,
    onPermissionAction: () -> Unit,
    onScanClick: () -> Unit,
    onOpenAlbum: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (albums.isEmpty()) {
        LibraryEmptyState("albums", audioPermission, onPermissionAction, onScanClick, modifier)
        return
    }
    val gridState = rememberLazyGridState()
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = 156.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = bottomInset + 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(items = albums, key = { it.id }, contentType = { "album" }) { album ->
            Column(
                modifier = Modifier.clickable { onOpenAlbum(album.id) },
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LumynArtwork(
                    artworkUri = album.artworkUri,
                    contentDescription = album.title,
                    cornerRadius = 16.dp,
                    fallbackIcon = LumynIcons.Album,
                    fallbackIconSize = 40.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                )
                Text(
                    text = album.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${album.artist} • ${formatSongCount(album.trackCount)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ArtistsTab(
    artists: List<ArtistSummary>,
    audioPermission: AudioPermissionStatus,
    bottomInset: androidx.compose.ui.unit.Dp,
    onPermissionAction: () -> Unit,
    onScanClick: () -> Unit,
    onOpenArtist: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (artists.isEmpty()) {
        LibraryEmptyState("artists", audioPermission, onPermissionAction, onScanClick, modifier)
        return
    }
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = bottomInset + 16.dp),
    ) {
        items(items = artists, key = { it.name }, contentType = { "artist" }) { artist ->
            ListItem(
                onClick = { onOpenArtist(artist.name) },
                leadingContent = {
                    LumynArtwork(
                        artworkUri = artist.artworkUri,
                        contentDescription = null,
                        shape = MaterialShapes.Cookie9Sided.toShape(),
                        fallbackIcon = LumynIcons.Person,
                        modifier = Modifier.size(48.dp),
                    )
                },
                supportingContent = {
                    val albumsText = if (artist.albumCount == 1) "1 album" else "${artist.albumCount} albums"
                    Text("$albumsText • ${formatSongCount(artist.trackCount)}")
                },
                content = { Text(artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

@Composable
private fun FoldersTab(
    folders: List<FolderSummary>,
    audioPermission: AudioPermissionStatus,
    bottomInset: androidx.compose.ui.unit.Dp,
    onPermissionAction: () -> Unit,
    onScanClick: () -> Unit,
    onOpenFolder: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (folders.isEmpty()) {
        LibraryEmptyState("folders", audioPermission, onPermissionAction, onScanClick, modifier)
        return
    }
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = bottomInset + 16.dp),
    ) {
        items(items = folders, key = { it.path }, contentType = { "folder" }) { folder ->
            ListItem(
                onClick = { onOpenFolder(folder.path) },
                leadingContent = {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(painter = painterResource(LumynIcons.Folder), contentDescription = null)
                        }
                    }
                },
                supportingContent = { Text(folder.path, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingContent = {
                    Text(
                        text = "${folder.trackCount} • ${formatDurationMs(folder.totalDurationMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                content = { Text(folder.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlaylistsTab(
    smartPlaylists: List<SmartPlaylistSummary>,
    playlists: List<PlaylistSummary>,
    notice: String?,
    bottomInset: androidx.compose.ui.unit.Dp,
    onCreateClick: () -> Unit,
    onImportM3uClick: () -> Unit,
    onImportCueClick: () -> Unit,
    onOpenSmartPlaylist: (SmartPlaylistType) -> Unit,
    onOpenPlaylist: (PlaylistSummary) -> Unit,
    onExportClick: (PlaylistSummary) -> Unit,
    onDeleteClick: (PlaylistSummary) -> Unit,
    onDismissNotice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 12.dp, bottom = bottomInset + 16.dp),
    ) {
        item(key = "playlist-actions", contentType = "action") {
            FlowRow(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(onClick = onCreateClick) {
                    Icon(
                        painter = painterResource(LumynIcons.Add),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text("New playlist", modifier = Modifier.padding(start = 8.dp))
                }
                OutlinedButton(onClick = onImportM3uClick) {
                    Icon(
                        painter = painterResource(LumynIcons.FileOpen),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text("Import M3U8", modifier = Modifier.padding(start = 6.dp))
                }
                OutlinedButton(onClick = onImportCueClick) {
                    Icon(
                        painter = painterResource(LumynIcons.FileOpen),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text("Import CUE", modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
        if (notice != null) {
            item(key = "playlist-notice", contentType = "notice") {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(notice, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = onDismissNotice) { Text("Dismiss") }
                    }
                }
            }
        }
        if (smartPlaylists.isNotEmpty()) {
            item(key = "smart-header", contentType = "section-header") {
                LumynSectionHeader("Smart playlists")
            }
            items(items = smartPlaylists, key = { "smart-${it.type.name}" }, contentType = { "smart-playlist" }) { sp ->
                val iconRes = when (sp.type) {
                    SmartPlaylistType.RECENTLY_PLAYED -> LumynIcons.History
                    SmartPlaylistType.MOST_PLAYED -> LumynIcons.BarChart
                    SmartPlaylistType.FORGOTTEN_GEMS -> LumynIcons.Star
                    SmartPlaylistType.HI_RES_COLLECTION -> LumynIcons.GraphicEq
                    SmartPlaylistType.FAVORITES -> LumynIcons.FavoriteFilled
                }
                ListItem(
                    onClick = { onOpenSmartPlaylist(sp.type) },
                    leadingContent = {
                        Surface(
                            shape = MaterialShapes.Cookie7Sided.toShape(),
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(40.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    painter = painterResource(iconRes),
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    },
                    supportingContent = { Text(sp.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = {
                        Text(
                            text = sp.trackCount.toString(),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    content = { Text(sp.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
        item(key = "user-playlists-header", contentType = "section-header") {
            LumynSectionHeader("Saved playlists")
        }
        if (playlists.isEmpty()) {
            item(key = "empty-playlists", contentType = "empty") {
                Text(
                    text = "No saved playlists yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                )
            }
        } else {
            items(items = playlists, key = { it.id }, contentType = { "playlist" }) { playlist ->
                ListItem(
                    onClick = { onOpenPlaylist(playlist) },
                    leadingContent = {
                        Icon(painter = painterResource(LumynIcons.QueueMusic), contentDescription = null)
                    },
                    supportingContent = { Text(formatSongCount(playlist.trackCount)) },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { onExportClick(playlist) },
                                shapes = IconButtonDefaults.shapes(),
                            ) {
                                Icon(
                                    painter = painterResource(LumynIcons.Save),
                                    contentDescription = "Export ${playlist.name} to M3U8",
                                )
                            }
                            IconButton(
                                onClick = { onDeleteClick(playlist) },
                                shapes = IconButtonDefaults.shapes(),
                            ) {
                                Icon(
                                    painter = painterResource(LumynIcons.Delete),
                                    contentDescription = "Delete ${playlist.name}",
                                )
                            }
                        }
                    },
                    content = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
    }
}

@Composable
private fun CreatePlaylistDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New playlist") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Playlist name") },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank(),
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LibraryEmptyState(
    tabLabel: String,
    audioPermission: AudioPermissionStatus,
    onPermissionAction: () -> Unit,
    onScanClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val needsPermission = audioPermission != AudioPermissionStatus.Granted
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Icon(
            painter = painterResource(if (needsPermission) LumynIcons.Lock else LumynIcons.LibraryOutlined),
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = if (needsPermission) "Audio permission required" else "No $tabLabel yet",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = if (needsPermission) {
                "Allow Lumyn to read local audio files so your library can be scanned. Everything stays on your device."
            } else {
                "No $tabLabel indexed yet. Rescan or check your folder filters in Settings."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (needsPermission) {
            Button(
                onClick = onPermissionAction,
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(
                    if (audioPermission == AudioPermissionStatus.PermanentlyDenied) {
                        "Open settings"
                    } else {
                        "Allow audio access"
                    },
                )
            }
        } else {
            OutlinedButton(
                onClick = onScanClick,
                shapes = ButtonDefaults.shapes(),
            ) {
                Text("Scan local music")
            }
        }
    }
}

private fun formatSongCount(count: Int): String = if (count == 1) "1 song" else "$count songs"

private fun readUriTextAndName(context: Context, uri: Uri): Pair<String, String>? {
    val name = context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: "Playlist"
    val content = context.contentResolver.openInputStream(uri)?.use { stream ->
        stream.bufferedReader(Charsets.UTF_8).readText()
    } ?: return null
    return content to name
}

private fun writeTextToUri(context: Context, uri: Uri, content: String): Boolean =
    runCatching {
        context.contentResolver.openOutputStream(uri)?.use { stream ->
            stream.bufferedWriter(Charsets.UTF_8).use { writer -> writer.write(content) }
            true
        } ?: false
    }.getOrDefault(false)
