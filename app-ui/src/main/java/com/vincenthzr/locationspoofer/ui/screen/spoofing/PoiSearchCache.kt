package com.vincenthzr.locationspoofer.ui.screen.spoofing

import com.vincenthzr.locationspoofer.data.model.MapEngine
import com.vincenthzr.locationspoofer.data.model.SearchMode
import com.vincenthzr.locationspoofer.ui.screen.AppPoiItem

data class PoiSearchKey(val query: String, val mode: SearchMode, val engine: MapEngine)

// Each submission has its own identity, even two requests for the same point source and text.
class PoiSearchRequest(val key: PoiSearchKey)

internal fun SpoofingUiState.beginSearch(request: PoiSearchRequest, elapsedMs: Long): SpoofingUiState {
    if (searchQuery != request.key.query) return this
    val fresh = cachedSearchKey == request.key && searchResults.isNotEmpty() &&
        elapsedMs - cachedSearchAt in 0L until 30_000L
    return copy(pendingSearch = if (fresh) null else request, showSearchResults = fresh)
}

internal fun SpoofingUiState.completeSearch(
    request: PoiSearchRequest, currentKey: PoiSearchKey, results: List<AppPoiItem>, elapsedMs: Long
): SpoofingUiState {
    if (pendingSearch !== request || request.key != currentKey || request.key.query != searchQuery) return this
    return copy(pendingSearch = null, searchResults = results, showSearchResults = results.isNotEmpty(),
        cachedSearchKey = request.key.takeIf { results.isNotEmpty() }, cachedSearchAt = elapsedMs)
}
