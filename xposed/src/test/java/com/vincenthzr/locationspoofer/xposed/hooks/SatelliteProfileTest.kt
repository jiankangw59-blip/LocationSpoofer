package com.vincenthzr.locationspoofer.xposed.hooks

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SatelliteProfileTest {
    @Test
    fun standardSatelliteCountUsesFixCountWhenVisibleSatellitesExceedIt() {
        assertEquals(
            mapOf(
                "satellites" to 12,
                "satellites_in_view" to 24,
                "satellites_visible" to 24,
                "satellites_used_in_fix" to 12
            ),
            extrasFor(24)
        )
    }

    @Test
    fun tenSatellitesKeepAllCountsAtTen() {
        assertEquals(
            mapOf(
                "satellites" to 10,
                "satellites_in_view" to 10,
                "satellites_visible" to 10,
                "satellites_used_in_fix" to 10
            ),
            extrasFor(10)
        )
    }

    private fun extrasFor(satelliteCount: Int): Map<String, Int> {
        val config = JSONObject().put("satellite_count", satelliteCount)
        val extras = mutableMapOf<String, Int>()
        SatelliteProfile.writeCounts(SatelliteProfile.count(config)) { key, value ->
            extras[key] = value
        }
        return extras
    }
}
