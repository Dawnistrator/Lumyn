package com.lunaria.music.ui

import android.content.IntentSender
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.material3.WideNavigationRailValue
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberWideNavigationRailState
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
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.window.core.layout.WindowSizeClass
import com.lunaria.core.designsystem.icon.LunariaIcons
import com.lunaria.core.designsystem.theme.LunariaMotionTokens
import com.lunaria.core.model.AudioCapabilitiesSnapshot
import com.lunaria.core.model.AudioPermissionStatus
import com.lunaria.core.model.PlaybackState
import com.lunaria.core.model.PlaylistSummary
import com.lunaria.core.model.Track
import com.lunaria.core.model.UserPreferences
import com.lunaria.feature.equalizer.EqualizerEntry
import com.lunaria.feature.equalizer.EqualizerScreen
import com.lunaria.feature.equalizer.EqualizerUiState
import com.lunaria.feature.home.HomeEntry
import com.lunaria.feature.home.HomeScreen
import com.lunaria.feature.home.HomeUiState
import com.lunaria.feature.home.ListeningStatsEntry
import com.lunaria.feature.library.LibraryCollectionKind
import com.lunaria.feature.library.LibraryDetailEntry
import com.lunaria.feature.library.LibraryEntry
import com.lunaria.feature.library.LibraryScreen
import com.lunaria.feature.library.LibraryUiState
import com.lunaria.feature.player.LyricsUiState
import com.lunaria.feature.player.MiniPlayerBar
import com.lunaria.feature.player.NowPlayingScreen
import com.lunaria.feature.player.NowPlayingSupportingPane
import com.lunaria.feature.player.PlayerActions
import com.lunaria.feature.player.TagEditorSheet
import com.lunaria.feature.player.TagEditorSubmission
import com.lunaria.feature.search.SearchEntry
import com.lunaria.feature.search.SearchScreen
import com.lunaria.feature.settings.SettingsEntry
import com.lunaria.feature.settings.SettingsScreen
import com.lunaria.music.navigation.HomeRoute
import com.lunaria.music.navigation.LibraryDetailRoute
import com.lunaria.music.navigation.LibraryRoute
import com.lunaria.music.navigation.ListeningStatsRoute
import com.lunaria.music.navigation.SearchRoute
import com.lunaria.music.navigation.SettingsRoute
import com.lunaria.music.navigation.TopLevelDestination
import kotlinx.coroutines.launch

/**
 * Adaptive, edge-to-edge app shell.
 *
 * - Compact (< 600 dp): mini-player above a short navigation bar; tapping opens Now Playing.
 * - Medium (600-839 dp): collapsed wide navigation rail, mini-player under content; tapping opens Now Playing.
 * - Expanded (>= 840 dp): expanded rail plus a persistent Now Playing supporting pane.
 */
@Composable
fun LunariaApp(
    audioPermission: AudioPermissionStatus,
    onPermissionAction: () -> Unit,
    capabilities: AudioCapabilitiesSnapshot,
    playbackState: PlaybackState,
    playlists: List<PlaylistSummary> = emptyList(),
    lyricsState: LyricsUiState = LyricsUiState(),
    useAlbumArtTheme: Boolean = true,
    darkTheme: Boolean = false,
    playerActions: PlayerActions = PlayerActions(),
    onPlayTracks: (List<Track>, Int) -> Unit = { _, _ -> },
    onPlayNext: (Track) -> Unit = {},
    onAddToQueue: (Track) -> Unit = {},
    onAddTracksToQueue: (List<Track>) -> Unit = {},
    onAddTrackToPlaylist: (PlaylistSummary, Track) -> Unit = { _, _ -> },
    onCreatePlaylistWithTrack: (String, Track) -> Unit = { _, _ -> },
    onSaveTrackTags: suspend (TagEditorSubmission) -> IntentSender? = { null },
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val windowSizeClass = currentWindowAdaptiveInfoV2().windowSizeClass
    val isMediumOrWider = windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    val isExpanded = windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)

    var isNowPlayingOpen by rememberSaveable { mutableStateOf(false) }
    var isEqualizerOpen by rememberSaveable { mutableStateOf(false) }
    var editingTrack by remember { mutableStateOf<Track?>(null) }
    var playlistPickerTrack by remember { mutableStateOf<Track?>(null) }

    val wiredActions = remember(playerActions) {
        playerActions.copy(
            onOpenEqualizer = { isEqualizerOpen = true },
            onEditTags = { track -> editingTrack = track },
            onAddToPlaylist = { track -> playlistPickerTrack = track },
        )
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val isDetailDestination = currentDestination?.hasRoute<LibraryDetailRoute>() == true ||
        currentDestination?.hasRoute<ListeningStatsRoute>() == true
    val navigateTo: (TopLevelDestination) -> Unit = { destination ->
        navController.navigate(destination.route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = !isDetailDestination
            }
            launchSingleTop = true
            restoreState = !isDetailDestination
        }
    }
    val isSelected: (TopLevelDestination) -> Boolean = { destination ->
        currentDestination?.hierarchy?.any { it.hasRoute(destination.routeClass) } == true
    }

    val isPreview = LocalInspectionMode.current

    editingTrack?.let { target ->
        TagEditorSheet(
            track = target,
            onSave = onSaveTrackTags,
            onDismiss = { editingTrack = null },
        )
    }

    playlistPickerTrack?.let { target ->
        AddToPlaylistSheet(
            track = target,
            playlists = playlists,
            onSelectPlaylist = { playlist ->
                onAddTrackToPlaylist(playlist, target)
                playlistPickerTrack = null
            },
            onCreateAndAdd = { name ->
                onCreatePlaylistWithTrack(name, target)
                playlistPickerTrack = null
            },
            onDismiss = { playlistPickerTrack = null },
        )
    }

    val navHost: @Composable (Modifier) -> Unit = { hostModifier ->
        LunariaNavHost(
            navController = navController,
            audioPermission = audioPermission,
            onPermissionAction = onPermissionAction,
            capabilities = capabilities,
            currentTrackId = playbackState.currentTrack?.id,
            onPlayTracks = onPlayTracks,
            onPlayNext = onPlayNext,
            onAddToQueue = onAddToQueue,
            onAddTracksToQueue = onAddTracksToQueue,
            onAddToPlaylist = { track -> playlistPickerTrack = track },
            onEditTrackTags = { track -> editingTrack = track },
            onOpenEqualizer = { isEqualizerOpen = true },
            modifier = hostModifier,
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (!isMediumOrWider) {
            Scaffold(
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    Column {
                        AnimatedVisibility(
                            visible = playbackState.currentTrack != null,
                            enter = slideInVertically(animationSpec = LunariaMotionTokens.spatialFast()) { it },
                            exit = slideOutVertically(animationSpec = LunariaMotionTokens.spatialFast()) { it },
                        ) {
                            MiniPlayerBar(
                                state = playbackState,
                                onPlayPause = wiredActions.onPlayPause,
                                onNext = wiredActions.onNext,
                                onOpenNowPlaying = { isNowPlayingOpen = true },
                                useAlbumArtTheme = useAlbumArtTheme,
                                darkTheme = darkTheme,
                            )
                        }
                        ShortNavigationBar {
                            TopLevelDestination.entries.forEach { destination ->
                                val selected = isSelected(destination)
                                ShortNavigationBarItem(
                                    selected = selected,
                                    onClick = { navigateTo(destination) },
                                    icon = {
                                        Icon(
                                            painter = painterResource(
                                                if (selected) destination.selectedIcon else destination.icon,
                                            ),
                                            contentDescription = null,
                                        )
                                    },
                                    label = { Text(destination.label) },
                                )
                            }
                        }
                    }
                },
            ) { innerPadding ->
                navHost(Modifier.padding(innerPadding).consumeWindowInsets(innerPadding))
            }
        } else {
            val railState = rememberWideNavigationRailState(
                initialValue = if (isExpanded) WideNavigationRailValue.Expanded else WideNavigationRailValue.Collapsed,
            )
            val scope = rememberCoroutineScope()
            LaunchedEffect(isExpanded) {
                if (isExpanded) railState.expand() else railState.collapse()
            }
            val railExpanded = railState.targetValue == WideNavigationRailValue.Expanded
            val bottomInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)

            Row(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                WideNavigationRail(
                    state = railState,
                    header = {
                        IconButton(
                            modifier = Modifier
                                .padding(start = 24.dp)
                                .semantics {
                                    stateDescription = if (railExpanded) "Expanded" else "Collapsed"
                                },
                            onClick = { scope.launch { railState.toggle() } },
                        ) {
                            Icon(
                                painter = painterResource(
                                    if (railExpanded) LunariaIcons.MenuOpen else LunariaIcons.Menu,
                                ),
                                contentDescription = if (railExpanded) "Collapse navigation" else "Expand navigation",
                            )
                        }
                    },
                ) {
                    TopLevelDestination.entries.forEach { destination ->
                        val selected = isSelected(destination)
                        WideNavigationRailItem(
                            selected = selected,
                            onClick = { navigateTo(destination) },
                            railExpanded = railExpanded,
                            icon = {
                                Icon(
                                    painter = painterResource(
                                        if (selected) destination.selectedIcon else destination.icon,
                                    ),
                                    contentDescription = null,
                                )
                            },
                            label = { Text(destination.label) },
                        )
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        navHost(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .then(if (isExpanded) Modifier else Modifier.consumeWindowInsets(bottomInsets)),
                        )
                        if (!isExpanded) {
                            AnimatedVisibility(
                                visible = playbackState.currentTrack != null,
                                enter = slideInVertically(animationSpec = LunariaMotionTokens.spatialFast()) { it },
                                exit = slideOutVertically(animationSpec = LunariaMotionTokens.spatialFast()) { it },
                            ) {
                                MiniPlayerBar(
                                    state = playbackState,
                                    onPlayPause = wiredActions.onPlayPause,
                                    onNext = wiredActions.onNext,
                                    onOpenNowPlaying = { isNowPlayingOpen = true },
                                    modifier = Modifier.windowInsetsPadding(bottomInsets),
                                    useAlbumArtTheme = useAlbumArtTheme,
                                    darkTheme = darkTheme,
                                )
                            }
                        }
                    }
                }

                if (isExpanded) {
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    NowPlayingSupportingPane(
                        state = playbackState,
                        signalSummary = "Signal path: ${capabilities.activeOutputSummary}",
                        lyricsState = lyricsState,
                        useAlbumArtTheme = useAlbumArtTheme,
                        darkTheme = darkTheme,
                        actions = wiredActions,
                        modifier = Modifier
                            .width(360.dp)
                            .fillMaxHeight()
                            .windowInsetsPadding(
                                WindowInsets.safeDrawing.only(
                                    WindowInsetsSides.Top + WindowInsetsSides.End + WindowInsetsSides.Bottom,
                                ),
                            ),
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = isNowPlayingOpen && !isExpanded,
            enter = slideInVertically(animationSpec = LunariaMotionTokens.spatialDefault()) { it },
            exit = slideOutVertically(animationSpec = LunariaMotionTokens.spatialDefault()) { it },
        ) {
            NowPlayingScreen(
                state = playbackState,
                lyricsState = lyricsState,
                useAlbumArtTheme = useAlbumArtTheme,
                darkTheme = darkTheme,
                actions = wiredActions,
                onCollapse = { isNowPlayingOpen = false },
            )
        }

        AnimatedVisibility(
            visible = isEqualizerOpen,
            enter = slideInVertically(animationSpec = LunariaMotionTokens.spatialDefault()) { it },
            exit = slideOutVertically(animationSpec = LunariaMotionTokens.spatialDefault()) { it },
        ) {
            if (isPreview) {
                EqualizerScreen(
                    uiState = EqualizerUiState(),
                    onNavigateBack = { isEqualizerOpen = false },
                )
            } else {
                EqualizerEntry(onNavigateBack = { isEqualizerOpen = false })
            }
        }

        val snackbarBottomPadding = when {
            isNowPlayingOpen || isEqualizerOpen || isExpanded -> 16.dp
            !isMediumOrWider && playbackState.currentTrack != null -> 156.dp
            !isMediumOrWider -> 88.dp
            playbackState.currentTrack != null -> 92.dp
            else -> 16.dp
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .padding(bottom = snackbarBottomPadding),
        )
    }
}

@Composable
private fun LunariaNavHost(
    navController: NavHostController,
    audioPermission: AudioPermissionStatus,
    onPermissionAction: () -> Unit,
    capabilities: AudioCapabilitiesSnapshot,
    currentTrackId: Long?,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onPlayNext: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onAddTracksToQueue: (List<Track>) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onEditTrackTags: (Track) -> Unit,
    onOpenEqualizer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isPreview = LocalInspectionMode.current
    val openDetail: (LibraryCollectionKind, String) -> Unit = { kind, key ->
        navController.navigate(LibraryDetailRoute(kind.name, key)) {
            launchSingleTop = true
        }
    }

    NavHost(navController = navController, startDestination = HomeRoute, modifier = modifier) {
        composable<HomeRoute> {
            if (isPreview) {
                HomeScreen(
                    audioPermission = audioPermission,
                    uiState = HomeUiState(capabilities = capabilities),
                    currentTrackId = currentTrackId,
                    onPermissionAction = onPermissionAction,
                    onScanClick = {},
                    onPlayTracks = onPlayTracks,
                    onToggleFavorite = {},
                    onEditTrackTags = onEditTrackTags,
                )
            } else {
                HomeEntry(
                    audioPermission = audioPermission,
                    currentTrackId = currentTrackId,
                    onPermissionAction = onPermissionAction,
                    onPlayTracks = onPlayTracks,
                    onPlayNext = onPlayNext,
                    onAddToQueue = onAddToQueue,
                    onAddToPlaylist = onAddToPlaylist,
                    onEditTrackTags = onEditTrackTags,
                    onOpenAlbum = { albumId -> openDetail(LibraryCollectionKind.ALBUM, albumId.toString()) },
                    onOpenSmartPlaylist = { type -> openDetail(LibraryCollectionKind.SMART, type.name) },
                    onOpenStats = {
                        navController.navigate(ListeningStatsRoute) { launchSingleTop = true }
                    },
                )
            }
        }
        composable<LibraryRoute> {
            if (isPreview) {
                LibraryScreen(
                    audioPermission = audioPermission,
                    uiState = LibraryUiState(),
                    currentTrackId = currentTrackId,
                    onPermissionAction = onPermissionAction,
                    onScanClick = {},
                    onSortOrderChange = {},
                    onPlayTracks = onPlayTracks,
                    onToggleFavorite = {},
                    onEditTrackTags = onEditTrackTags,
                    onCreatePlaylist = {},
                    onDeletePlaylist = {},
                )
            } else {
                LibraryEntry(
                    audioPermission = audioPermission,
                    currentTrackId = currentTrackId,
                    onPermissionAction = onPermissionAction,
                    onPlayTracks = onPlayTracks,
                    onPlayNext = onPlayNext,
                    onAddToQueue = onAddToQueue,
                    onAddToPlaylist = onAddToPlaylist,
                    onEditTrackTags = onEditTrackTags,
                    onOpenAlbum = { albumId -> openDetail(LibraryCollectionKind.ALBUM, albumId.toString()) },
                    onOpenArtist = { artist -> openDetail(LibraryCollectionKind.ARTIST, artist) },
                    onOpenFolder = { folder -> openDetail(LibraryCollectionKind.FOLDER, folder) },
                    onOpenPlaylist = { id -> openDetail(LibraryCollectionKind.PLAYLIST, id.toString()) },
                    onOpenSmartPlaylist = { type -> openDetail(LibraryCollectionKind.SMART, type.name) },
                )
            }
        }
        composable<LibraryDetailRoute> { entry ->
            val route = entry.toRoute<LibraryDetailRoute>()
            LibraryDetailEntry(
                kind = route.kind,
                key = route.key,
                currentTrackId = currentTrackId,
                onNavigateBack = { navController.popBackStack() },
                onPlayTracks = onPlayTracks,
                onPlayNext = onPlayNext,
                onAddToQueue = onAddToQueue,
                onAddTracksToQueue = onAddTracksToQueue,
                onAddToPlaylist = onAddToPlaylist,
                onEditTrackTags = onEditTrackTags,
            )
        }
        composable<ListeningStatsRoute> {
            ListeningStatsEntry(
                currentTrackId = currentTrackId,
                onNavigateBack = { navController.popBackStack() },
                onOpenArtist = { artist -> openDetail(LibraryCollectionKind.ARTIST, artist) },
                onPlayTracks = onPlayTracks,
                onPlayNext = onPlayNext,
                onAddToQueue = onAddToQueue,
                onAddToPlaylist = onAddToPlaylist,
                onEditTrackTags = onEditTrackTags,
            )
        }
        composable<SearchRoute> {
            if (isPreview) {
                SearchScreen(currentTrackId = currentTrackId, onPlayTracks = onPlayTracks)
            } else {
                SearchEntry(
                    currentTrackId = currentTrackId,
                    onPlayTracks = onPlayTracks,
                    onPlayNext = onPlayNext,
                    onAddToQueue = onAddToQueue,
                    onAddToPlaylist = onAddToPlaylist,
                    onEditTrackTags = onEditTrackTags,
                )
            }
        }
        composable<SettingsRoute> {
            if (isPreview) {
                SettingsScreen(
                    preferences = UserPreferences(),
                    capabilities = capabilities,
                    audioPermission = audioPermission,
                    onThemeModeChange = {},
                    onDynamicColorChange = {},
                    onAlbumArtThemeChange = {},
                    onAudioOutputModeChange = {},
                    onOpenEqualizer = onOpenEqualizer,
                    onPermissionAction = onPermissionAction,
                )
            } else {
                SettingsEntry(
                    audioPermission = audioPermission,
                    onPermissionAction = onPermissionAction,
                    onOpenEqualizer = onOpenEqualizer,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddToPlaylistSheet(
    track: Track,
    playlists: List<PlaylistSummary>,
    onSelectPlaylist: (PlaylistSummary) -> Unit,
    onCreateAndAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }

    if (showCreateDialog) {
        var name by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
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
                    onClick = {
                        showCreateDialog = false
                        onCreateAndAdd(name)
                    },
                    enabled = name.isNotBlank(),
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("Cancel") }
            },
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Add to playlist", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                FilledTonalButton(onClick = { showCreateDialog = true }) {
                    Icon(
                        painter = painterResource(LunariaIcons.Add),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text("New", modifier = Modifier.padding(start = 6.dp))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (playlists.isEmpty()) {
                Text(
                    text = "No playlists yet. Tap \"New\" above to create one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            } else {
                LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(
                        items = playlists,
                        key = { it.id },
                        contentType = { "playlist" },
                    ) { playlist ->
                        val countLabel = if (playlist.trackCount == 1) "1 song" else "${playlist.trackCount} songs"
                        ListItem(
                            onClick = { onSelectPlaylist(playlist) },
                            leadingContent = {
                                Icon(painterResource(LunariaIcons.QueueMusic), contentDescription = null)
                            },
                            supportingContent = { Text(countLabel) },
                            content = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }
        }
    }
}
