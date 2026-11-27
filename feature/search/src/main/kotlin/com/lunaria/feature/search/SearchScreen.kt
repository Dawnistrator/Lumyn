package com.lunaria.feature.search

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExpandedFullScreenSearchBar
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lunaria.core.designsystem.component.TrackListItem
import com.lunaria.core.designsystem.icon.LunariaIcons
import com.lunaria.core.model.SearchFilterChip
import com.lunaria.core.model.Track
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private val ChipLabels = listOf(
    SearchFilterChip.HI_RES to "Hi-Res",
    SearchFilterChip.LOSSLESS to "Lossless",
    SearchFilterChip.FLAC to "FLAC",
    SearchFilterChip.FAVORITES to "Favorites",
)

@Composable
fun SearchEntry(
    currentTrackId: Long?,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onPlayNext: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onAddToPlaylist: (Track) -> Unit = {},
    onEditTrackTags: (Track) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    SearchScreen(
        uiState = uiState,
        currentTrackId = currentTrackId,
        onQueryChange = viewModel::updateQuery,
        onToggleFilter = viewModel::toggleFilter,
        onClearFilters = viewModel::clearFilters,
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
fun SearchScreen(
    uiState: SearchUiState = SearchUiState(),
    currentTrackId: Long? = null,
    onQueryChange: (String) -> Unit = {},
    onToggleFilter: (SearchFilterChip) -> Unit = {},
    onClearFilters: () -> Unit = {},
    onPlayTracks: (List<Track>, Int) -> Unit = { _, _ -> },
    onPlayNext: (Track) -> Unit = {},
    onAddToQueue: (Track) -> Unit = {},
    onAddToPlaylist: (Track) -> Unit = {},
    onToggleFavorite: (Track) -> Unit = {},
    onEditTrackTags: (Track) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val textFieldState = rememberTextFieldState(initialText = uiState.query)
    val searchBarState = rememberSearchBarState()
    val scope = rememberCoroutineScope()

    val currentOnQueryChange by rememberUpdatedState(onQueryChange)

    LaunchedEffect(textFieldState) {
        snapshotFlow { textFieldState.text.toString() }
            .distinctUntilChanged()
            .collect { currentOnQueryChange(it) }
    }

    val inputField = @Composable {
        SearchBarDefaults.InputField(
            textFieldState = textFieldState,
            searchBarState = searchBarState,
            onSearch = { scope.launch { searchBarState.animateToCollapsed() } },
            placeholder = { Text("Search songs, albums, artists, folders") },
            leadingIcon = {
                Icon(painter = painterResource(LunariaIcons.Search), contentDescription = null)
            },
            trailingIcon = if (textFieldState.text.isNotEmpty()) {
                {
                    IconButton(onClick = { textFieldState.clearText() }) {
                        Icon(
                            painter = painterResource(LunariaIcons.Close),
                            contentDescription = "Clear search",
                        )
                    }
                }
            } else {
                null
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Top + WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                ),
            ),
    ) {
        SearchBar(
            state = searchBarState,
            inputField = inputField,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        ExpandedFullScreenSearchBar(state = searchBarState, inputField = inputField) {
            SearchResultsList(
                results = uiState.results,
                totalTrackCount = uiState.totalTrackCount,
                hasActiveQueryOrFilter = uiState.query.isNotBlank() || uiState.activeFilters.isNotEmpty(),
                currentTrackId = currentTrackId,
                onClearFilters = {
                    textFieldState.clearText()
                    onClearFilters()
                },
                onPlayTracks = { list, index ->
                    scope.launch { searchBarState.animateToCollapsed() }
                    onPlayTracks(list, index)
                },
                onPlayNext = onPlayNext,
                onAddToQueue = onAddToQueue,
                onAddToPlaylist = onAddToPlaylist,
                onToggleFavorite = onToggleFavorite,
                onEditTrackTags = onEditTrackTags,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChipLabels.forEach { (chip, label) ->
                val selected = chip in uiState.activeFilters
                FilterChip(
                    selected = selected,
                    onClick = { onToggleFilter(chip) },
                    label = { Text(label) },
                    leadingIcon = if (selected) {
                        {
                            Icon(
                                painter = painterResource(LunariaIcons.Check),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }

        SearchResultsList(
            results = uiState.results,
            totalTrackCount = uiState.totalTrackCount,
            hasActiveQueryOrFilter = uiState.query.isNotBlank() || uiState.activeFilters.isNotEmpty(),
            currentTrackId = currentTrackId,
            onClearFilters = {
                textFieldState.clearText()
                onClearFilters()
            },
            onPlayTracks = onPlayTracks,
            onPlayNext = onPlayNext,
            onAddToQueue = onAddToQueue,
            onAddToPlaylist = onAddToPlaylist,
            onToggleFavorite = onToggleFavorite,
            onEditTrackTags = onEditTrackTags,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SearchResultsList(
    results: List<Track>,
    totalTrackCount: Int,
    hasActiveQueryOrFilter: Boolean,
    currentTrackId: Long?,
    onClearFilters: () -> Unit,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onPlayNext: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onToggleFavorite: (Track) -> Unit,
    onEditTrackTags: (Track) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (results.isEmpty()) {
        val message = when {
            totalTrackCount == 0 -> "No tracks indexed yet. Allow audio access and scan your library first."
            hasActiveQueryOrFilter -> "No tracks match your search or active filters."
            else -> "Type keywords to search local music."
        }
        EmptyResults(
            message = message,
            showResetButton = hasActiveQueryOrFilter && totalTrackCount > 0,
            onResetFilters = onClearFilters,
            modifier = modifier,
        )
        return
    }

    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
    ) {
        itemsIndexed(items = results, key = { _, track -> track.id }, contentType = { _, _ -> "track" }) { index, track ->
            TrackListItem(
                track = track,
                isPlaying = track.id == currentTrackId,
                onClick = { onPlayTracks(results, index) },
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
private fun EmptyResults(
    message: String,
    showResetButton: Boolean,
    onResetFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (showResetButton) {
            OutlinedButton(onClick = onResetFilters) {
                Text("Reset filters")
            }
        }
    }
}
