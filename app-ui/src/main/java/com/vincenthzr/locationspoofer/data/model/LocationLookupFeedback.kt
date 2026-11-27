package com.vincenthzr.locationspoofer.data.model

import androidx.annotation.StringRes
import kotlin.math.abs

enum class LocationLookupSource { WIFI, WIFI_METADATA, WIGLE_WIFI, CELL, OPEN_CELL, BLUETOOTH }

data class LocationLookupLine(
    @StringRes val text: Int,
    val arguments: List<Int> = emptyList(),
    val fromPreview: Boolean = false
)

data class LocationLookupFeedback(
    val latitude: Double,
    val longitude: Double,
    val lines: Map<LocationLookupSource, LocationLookupLine>,
    val identity: Any = Any(),
    val dismissed: Boolean = false
) {
    fun matchesPoint(lat: Double?, lng: Double?): Boolean =
        lat != null && lng != null && abs(latitude - lat) <= 0.000001 && abs(longitude - lng) <= 0.000001

    fun blocksRepeat(lat: Double, lng: Double, explicitlyStarted: Boolean = false): Boolean =
        dismissed && !explicitlyStarted && matchesPoint(lat, lng)

    fun dismiss(): LocationLookupFeedback = copy(dismissed = true, lines = emptyMap())

    fun updateLine(
        from: LocationLookupFeedback,
        source: LocationLookupSource,
        line: LocationLookupLine
    ): LocationLookupFeedback =
        if (dismissed || identity !== from.identity) this else copy(lines = lines + (source to line))
}
