package com.lumyn.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumyn.core.database.repository.LocalMusicRepository
import com.lumyn.core.model.SearchCriteria
import com.lumyn.core.model.SearchFilterChip
import com.lumyn.core.model.Track
import com.lumyn.core.model.matches
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val activeFilters: Set<SearchFilterChip> = emptySet(),
    val results: List<Track> = emptyList(),
    val totalTrackCount: Int = 0,
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val musicRepository: LocalMusicRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val filters = MutableStateFlow<Set<SearchFilterChip>>(emptySet())

    val uiState: StateFlow<SearchUiState> = combine(
        musicRepository.tracks,
        query,
        filters,
    ) { allTracks, q, activeFilters ->
        val criteria = SearchCriteria(q, activeFilters)
        val filtered = if (q.isBlank() && activeFilters.isEmpty()) {
            allTracks
        } else {
            allTracks.filter { it.matches(criteria) }
        }
        SearchUiState(
            query = q,
            activeFilters = activeFilters,
            results = filtered,
            totalTrackCount = allTracks.size,
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SearchUiState(),
        )

    fun updateQuery(newQuery: String) {
        query.value = newQuery
    }

    fun toggleFilter(chip: SearchFilterChip) {
        val current = filters.value
        filters.value = if (chip in current) current - chip else current + chip
    }

    fun clearFilters() {
        query.value = ""
        filters.value = emptySet()
    }

    fun toggleFavorite(track: Track) {
        viewModelScope.launch { musicRepository.toggleFavorite(track.id, !track.isFavorite) }
    }
}
