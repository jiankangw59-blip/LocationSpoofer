package com.vincenthzr.locationspoofer.xposed.hooks

import android.os.Bundle
import org.json.JSONObject

/** Keeps satellite metadata identical across Location, GNSS and NMEA paths. */
internal object SatelliteProfile {
    const val DEFAULT_COUNT = 10
    private const val MIN_COUNT = 4
    private const val MAX_COUNT = 32

    fun count(config: JSONObject?): Int =
        config?.optInt("satellite_count", DEFAULT_COUNT)?.coerceIn(MIN_COUNT, MAX_COUNT)
            ?: DEFAULT_COUNT

    fun usedCount(visible: Int): Int = visible.coerceIn(MIN_COUNT, MAX_COUNT).coerceAtMost(12)

    fun apply(extras: Bundle, satelliteCount: Int = DEFAULT_COUNT) {
        writeCounts(satelliteCount) { key, value -> extras.putInt(key, value) }
        extras.remove("mockLocation")
    }

    internal inline fun writeCounts(satelliteCount: Int, putInt: (String, Int) -> Unit) {
        val visible = satelliteCount.coerceIn(MIN_COUNT, MAX_COUNT)
        val used = usedCount(visible)
        // Android's legacy key counts the satellites used to derive the fix.
        putInt("satellites", used)
        putInt("satellites_in_view", visible)
        putInt("satellites_visible", visible)
        putInt("satellites_used_in_fix", used)
    }
}
