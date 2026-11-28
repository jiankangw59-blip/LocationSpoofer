package com.vincenthzr.locationspoofer.utils

/** ScanResult.timestamp uses microseconds since boot, not UTC or time of insertion into the database. */
object WifiObservationAge {
    const val MAX_AGE_US = 10_000_000L
    fun isRecent(seenUs: Long, nowUs: Long): Boolean =
        seenUs > 0L && seenUs <= nowUs && nowUs - seenUs <= MAX_AGE_US
}
