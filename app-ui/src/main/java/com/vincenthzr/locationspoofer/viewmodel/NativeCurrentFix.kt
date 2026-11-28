package com.vincenthzr.locationspoofer.viewmodel

// Policy for the application's "current location" lookup, not a cache for simulated positions.
private const val MAX_NATIVE_FIX_AGE_NS = 30_000_000_000L

internal fun validCurrentCoordinates(lat: Double, lng: Double): Boolean =
    lat in -90.0..90.0 && lng in -180.0..180.0

internal data class NativeCurrentFix(val lat: Double, val lng: Double, val elapsedNs: Long) {
    fun isFreshAt(nowNs: Long): Boolean = elapsedNs > 0 && elapsedNs <= nowNs &&
        nowNs - elapsedNs <= MAX_NATIVE_FIX_AGE_NS && validCurrentCoordinates(lat, lng)
}

internal fun newestFreshNativeFix(candidates: List<NativeCurrentFix>, nowNs: Long): NativeCurrentFix? =
    candidates.filter { it.isFreshAt(nowNs) }.maxByOrNull { it.elapsedNs }
