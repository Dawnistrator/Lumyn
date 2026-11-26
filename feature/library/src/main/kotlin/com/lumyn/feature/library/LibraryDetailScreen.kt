package com.lumyn.feature.library

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumyn.core.designsystem.component.LumynArtwork
import com.lumyn.core.designsystem.component.TrackListItem
import com.lumyn.core.designsystem.component.formatDurationMs
import com.lumyn.core.designsystem.icon.LumynIcons
import com.lumyn.core.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LibraryDetailEntry(
    kind: String,
    key: String,
    currentTrackId: Long?,
    onNavigateBack: () -> Unit,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onPlayNext: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onAddTracksToQueue: (List<Track>) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onEditTrackTags: (Track) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryDetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(kind, key) {
        viewModel.load(kind, key)
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(uiState.deleted) {
        if (uiState.deleted) onNavigateBack()
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/x-mpegurl"),
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val content = viewModel.exportPlaylistM3u8()
                val ok = withContext(Dispatchers.IO) { writePlaylistText(context, uri, content) }
                if (ok) viewModel.notifyExported()
            }
        }
    }

    LibraryDetailScreen(
        uiState = uiState,
        currentTrackId = currentTrackId,
        onNavigateBack = onNavigateBack,
        onPlayTracks = onPlayTracks,
        onPlayNext = onPlayNext,
        onAddToQueue = onAddToQueue,
        onAddTracksToQueue = onAddTracksToQueue,
        onAddToPlaylist = onAddToPlaylist,
        onEditTrackTags = onEditTrackTags,
        onToggleFavorite = viewModel::toggleFavorite,
        onRemoveFromPlaylist = viewModel::removeFromPlaylist,
        onRenamePlaylist = viewModel::renamePlaylist,
        onExportPlaylistClick = { exportLauncher.launch("${uiState.title}.m3u8") },
        onDeletePlaylist = viewModel::deletePlaylist,
        onDismissNotice = viewModel::clearNotice,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LibraryDetailScreen(
    uiState: LibraryDetailUiState,
    currentTrackId: Long?,
    onNavigateBack: () -> Unit,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onPlayNext: (Track) -> Unit = {},
    onAddToQueue: (Track) -> Unit = {},
    onAddTracksToQueue: (List<Track>) -> Unit = {},
    onAddToPlaylist: (Track) -> Unit = {},
    onEditTrackTags: (Track) -> Unit = {},
    onToggleFavorite: (Track) -> Unit = {},
    onRemoveFromPlaylist: (Long) -> Unit = {},
    onRenamePlaylist: (String) -> Unit = {},
    onExportPlaylistClick: () -> Unit = {},
    onDeletePlaylist: () -> Unit = {},
    onDismissNotice: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var showRenameDialog by rememberSaveable { mutableStateOf(false) }
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }

    if (showRenameDialog) {
        var newName by rememberSaveable(uiState.title) { mutableStateOf(uiState.title) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename playlist") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    label = { Text("Playlist name") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRenamePlaylist(newName)
                        showRenameDialog = false
                    },
                    enabled = newName.isNotBlank(),
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete playlist?") },
            text = { Text("\"${uiState.title}\" will be removed from your local library.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDeletePlaylist()
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            painter = painterResource(LumynIcons.ArrowBack),
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    if (uiState.isUserPlaylist) {
                        androidx.compose.foundation.layout.Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(
                                    painter = painterResource(LumynIcons.MoreVert),
                                    contentDescription = "Playlist options",
                                )
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Rename") },
                                    leadingIcon = {
                                        Icon(painterResource(LumynIcons.Edit), contentDescription = null)
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        showRenameDialog = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Export M3U8") },
                                    leadingIcon = {
                                        Icon(painterResource(LumynIcons.Save), contentDescription = null)
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        onExportPlaylistClick()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete playlist") },
                                    leadingIcon = {
                                        Icon(painterResource(LumynIcons.Delete), contentDescription = null)
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        showDeleteDialog = true
                                    },
                                )
                            }
                        }
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
        ) {
            item(key = "detail-header", contentType = "header") {
                CollectionHeader(
                    uiState = uiState,
                    onPlayAll = { if (uiState.tracks.isNotEmpty()) onPlayTracks(uiState.tracks, 0) },
                    onShuffleAll = { if (uiState.tracks.isNotEmpty()) onPlayTracks(uiState.tracks.shuffled(), 0) },
                    onQueueAll = { if (uiState.tracks.isNotEmpty()) onAddTracksToQueue(uiState.tracks) },
                )
            }

            uiState.notice?.let { msg ->
                item(key = "detail-notice", contentType = "notice") {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(msg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = onDismissNotice) { Text("Dismiss") }
                        }
                    }
                }
            }

            if (uiState.tracks.isEmpty()) {
                item(key = "detail-empty", contentType = "empty") {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(
                            text = if (uiState.isUserPlaylist) {
                                "No tracks in this playlist yet. Use \"Add to playlist\" from any song menu."
                            } else {
                                "No tracks match this collection yet."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(20.dp),
                        )
                    }
                }
            } else {
                itemsIndexed(
                    items = uiState.tracks,
                    key = { _, track -> track.id },
                    contentType = { _, _ -> "track" },
                ) { index, track ->
                    TrackListItem(
                        track = track,
                        isPlaying = track.id == currentTrackId,
                        onClick = { onPlayTracks(uiState.tracks, index) },
                        onToggleFavorite = { onToggleFavorite(track) },
                        onPlayNext = { onPlayNext(track) },
                        onAddToQueue = { onAddToQueue(track) },
                        onAddToPlaylist = { onAddToPlaylist(track) },
                        onRemoveFromPlaylist = if (uiState.isUserPlaylist) {
                            { onRemoveFromPlaylist(track.id) }
                        } else {
                            null
                        },
                        onEditTags = { onEditTrackTags(track) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CollectionHeader(
    uiState: LibraryDetailUiState,
    onPlayAll: () -> Unit,
    onShuffleAll: () -> Unit,
    onQueueAll: () -> Unit,
) {
    val fallbackIcon = when (uiState.kind) {
        LibraryCollectionKind.ALBUM -> LumynIcons.Album
        LibraryCollectionKind.ARTIST -> LumynIcons.Person
        LibraryCollectionKind.FOLDER -> LumynIcons.Folder
        LibraryCollectionKind.PLAYLIST -> LumynIcons.QueueMusic
        LibraryCollectionKind.SMART -> LumynIcons.Star
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.extraLargeIncreased,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                LumynArtwork(
                    artworkUri = uiState.artworkUri,
                    contentDescription = uiState.title,
                    cornerRadius = 18.dp,
                    fallbackIcon = fallbackIcon,
                    fallbackIconSize = 36.dp,
                    modifier = Modifier.size(84.dp),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = uiState.title,
                        style = MaterialTheme.typography.titleLargeEmphasized,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (uiState.subtitle.isNotBlank()) {
                        Text(
                            text = uiState.subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val count = uiState.tracks.size
                        val countText = if (count == 1) "1 song" else "$count songs"
                        Text(
                            text = "$countText • ${formatDurationMs(uiState.totalDurationMs)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (uiState.hasHiRes) {
                            Surface(
                                shape = MaterialTheme.shapes.extraSmall,
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            ) {
                                Text(
                                    text = "Hi-Res",
                                    style = MaterialTheme.typography.labelSmallEmphasized,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                )
                            }
                        }
                    }
                }
            }

            if (uiState.tracks.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onPlayAll,
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Icon(
                            painter = painterResource(LumynIcons.PlayArrow),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text("Play all", modifier = Modifier.padding(start = 6.dp))
                    }
                    FilledTonalButton(
                        onClick = onShuffleAll,
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Icon(
                            painter = painterResource(LumynIcons.Shuffle),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text("Shuffle", modifier = Modifier.padding(start = 6.dp))
                    }
                    OutlinedButton(
                        onClick = onQueueAll,
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Icon(
                            painter = painterResource(LumynIcons.PlaylistAdd),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text("Add to queue", modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
    }
}

private fun writePlaylistText(context: Context, uri: Uri, content: String): Boolean =
    runCatching {
        context.contentResolver.openOutputStream(uri)?.use { stream ->
            stream.bufferedWriter(Charsets.UTF_8).use { writer -> writer.write(content) }
            true
        } ?: false
    }.getOrDefault(false)
