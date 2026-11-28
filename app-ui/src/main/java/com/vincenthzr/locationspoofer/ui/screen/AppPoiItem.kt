package com.vincenthzr.locationspoofer.ui.screen

import com.vincenthzr.locationspoofer.utils.CoordinateUtils

data class AppPoiItem(
    val title: String,
    val snippet: String,
    val lat: Double,
    val lng: Double,
    val isOnlineResult: Boolean = false
)

/** Match the Google camera adapter: convert once, when the SDK result enters the app. */
internal fun googlePlaceItem(title: String, snippet: String, wgsLat: Double, wgsLng: Double): AppPoiItem {
    val point = CoordinateUtils.wgs84ToGcj02(wgsLat, wgsLng)
    return AppPoiItem(title, snippet, point.lat, point.lng)
}
