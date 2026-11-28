package com.vincenthzr.locationspoofer.ui.screen.spoofing

import com.vincenthzr.locationspoofer.data.model.MapEngine
import com.vincenthzr.locationspoofer.data.model.SearchMode
import com.vincenthzr.locationspoofer.ui.screen.AppPoiItem
import org.junit.Assert.*
import org.junit.Test

class PoiSearchCacheTest {
    private val key = PoiSearchKey("square", SearchMode.NETWORK, MapEngine.GOOGLE)
    private val result = listOf(AppPoiItem("Square", "", 0.0, 0.0))
    private val initial = SpoofingUiState(searchQuery = key.query)

    private fun filled(): SpoofingUiState {
        val request = PoiSearchRequest(key)
        return initial.beginSearch(request, 90L).completeSearch(request, key, result, 100L)
    }

    @Test fun readsDoNotExtendTtlAndBackwardClockIsNeverFresh() {
        val request = PoiSearchRequest(key)
        val hit = filled().beginSearch(request, 30_099L)
        assertTrue(hit.showSearchResults)
        assertNull(hit.pendingSearch)
        assertEquals(100L, hit.cachedSearchAt)
        assertSame(request, hit.beginSearch(request, 30_100L).pendingSearch)
        assertSame(request, hit.beginSearch(request, 99L).pendingSearch)
    }

    @Test fun sameTextFromAnotherSourceOrEngineCannotReuseOrPublishCachedResults() {
        val old = PoiSearchRequest(key)
        for (other in listOf(key.copy(mode = SearchMode.LOCAL), key.copy(engine = MapEngine.BAIDU))) {
            val next = PoiSearchRequest(other)
            val pending = filled().beginSearch(next, 110L)
            assertSame(next, pending.pendingSearch)
            assertFalse(pending.showSearchResults)
            assertSame(pending, pending.completeSearch(old, other, result, 120L))
            assertSame(pending, pending.completeSearch(next, key, result, 120L))
        }
    }

    @Test fun supersededAndEditedQueriesCannotPublishLateCallbacks() {
        val first = PoiSearchRequest(key)
        val second = PoiSearchRequest(key)
        val pending = initial.beginSearch(first, 200L).beginSearch(second, 201L)
        assertSame(pending, pending.completeSearch(first, key, result, 205L))
        val edited = pending.copy(searchQuery = "new")
        assertSame(edited, edited.completeSearch(second, key, result, 206L))
        val complete = pending.completeSearch(second, key, result, 208L)
        assertEquals(result, complete.searchResults)
        assertEquals(208L, complete.cachedSearchAt)
        assertNull(complete.pendingSearch)
    }
}
