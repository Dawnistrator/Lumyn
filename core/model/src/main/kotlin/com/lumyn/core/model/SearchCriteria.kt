package com.lumyn.core.model

enum class SearchFilterChip {
    HI_RES,
    LOSSLESS,
    FLAC,
    FAVORITES,
}

data class SearchCriteria(
    val query: String = "",
    val filters: Set<SearchFilterChip> = emptySet(),
)

fun Track.matches(criteria: SearchCriteria): Boolean {
    for (chip in criteria.filters) {
        val ok = when (chip) {
            SearchFilterChip.HI_RES -> audioSpec.isHiRes
            SearchFilterChip.LOSSLESS -> audioSpec.isLossless
            SearchFilterChip.FLAC -> audioSpec.codec.equals("FLAC", ignoreCase = true)
            SearchFilterChip.FAVORITES -> isFavorite
        }
        if (!ok) return false
    }

    val needle = criteria.query.trim()
    if (needle.isEmpty()) return true
    return title.contains(needle, ignoreCase = true) ||
        artist.contains(needle, ignoreCase = true) ||
        album.contains(needle, ignoreCase = true) ||
        albumArtist.contains(needle, ignoreCase = true) ||
        folderPath.contains(needle, ignoreCase = true) ||
        fileName.contains(needle, ignoreCase = true)
}
