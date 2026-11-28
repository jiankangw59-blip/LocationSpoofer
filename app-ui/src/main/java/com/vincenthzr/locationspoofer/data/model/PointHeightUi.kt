package com.vincenthzr.locationspoofer.data.model

import com.vincenthzr.locationspoofer.data.elevation.TargetElevationSource
import com.vincenthzr.locationspoofer.utils.CoordinateUtils

sealed interface PointHeightUi {
    data object Idle : PointHeightUi
    data object Loading : PointHeightUi
    data object Unavailable : PointHeightUi
    data class Ready(val surfaceMslMeters: Double, val ellipsoidMeters: Double, val fromCache: Boolean) : PointHeightUi
}

internal data class PointHeightTarget(val key: String, val wgs: CoordinateUtils.LatLng)
internal fun pointHeightTarget(lat: Double?, lon: Double?): PointHeightTarget? {
    if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
    val wgs = CoordinateUtils.gcj02ToWgs84(lat, lon)
    return TargetElevationSource.key(wgs.lat, wgs.lng)?.let { PointHeightTarget(it, wgs) }
}
