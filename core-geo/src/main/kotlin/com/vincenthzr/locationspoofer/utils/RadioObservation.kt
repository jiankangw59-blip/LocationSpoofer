package com.vincenthzr.locationspoofer.utils

import kotlin.math.roundToInt
import kotlin.math.sin

/** Lazily sampled virtual observations. No worker, hardware scan, or read-count random state. */
object RadioObservation {
    const val WIFI_PERIOD_NS = 5_000_000_000L
    const val CELL_PERIOD_NS = 2_000_000_000L
    fun timestamp(elapsedNanos: Long, periodNanos: Long): Long {
        require(elapsedNanos >= 0 && periodNanos > 0)
        return elapsedNanos - elapsedNanos % periodNanos
    }
    fun dbm(base: Int, identity: String, timestampNanos: Long, enabled: Boolean): Int {
        if (!enabled) return base
        val phase = (identity.hashCode().toLong() and 0xffff) / 65536.0 * 2 * Math.PI
        val variation = (2 * sin(timestampNanos / 1_000_000_000.0 * 0.35 + phase)).roundToInt()
        return (base + variation).coerceIn(-140, -35)
    }
}
