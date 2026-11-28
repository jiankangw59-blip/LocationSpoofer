package com.vincenthzr.locationspoofer.utils

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PointHeightBindingTest {
    private fun point(lat: Double = 0.0, lng: Double = -35.0): JSONObject = JSONObject()
        .put("lat", lat).put("lng", lng).put("active", true).put("sim_mode", "STILL").put("is_route_mode", false)

    @Test fun exactStoredPointCanRecoverWithoutAnotherLookup() {
        val json = point()
        PointHeightBinding.assign(json, 25.0, -12.0)
        val restart = JSONObject(json.toString())
        assertEquals(-12.0, PointHeightBinding.restoredMeters(restart, 0.0, -35.0)!!, 0.0)
        assertNull(PointHeightBinding.restoredMeters(restart, 0.0, -35.000001))
        assertNull(PointHeightBinding.restoredMeters(point().put("altitude", 25.0), 0.0, -35.0))
        assertTrue(PointHeightBinding.validPosition(0.0, 0.0))
        assertFalse(PointHeightBinding.validPosition(null, 0.0))
        assertFalse(PointHeightBinding.canRestorePosition(null, 0.0, 0.0))
        assertFalse(PointHeightBinding.canRestorePosition(point(1.0, 0.0), 0.0, 0.0))
        assertFalse(PointHeightBinding.canRestorePosition(point(0.0, 0.0).put("active", false), 0.0, 0.0))
        assertTrue(PointHeightBinding.canRestorePosition(point(0.0, 0.0), 0.0, 0.0))
        assertTrue(PointHeightBinding.canRestorePosition(restart, 0.0, -35.0))
    }

    @Test fun unrelatedPatchPreservesBoundZeroHeightAndDoesNotReadManualSetting() {
        val json = point()
        PointHeightBinding.assign(json, 21.0, 0.0)
        json.put("wifi_json", JSONObject())
        PointHeightBinding.reconcilePatch(json) { error("unrelated patch read manual height") }
        assertEquals(0.0, PointHeightBinding.restoredMeters(json, 0.0, -35.0)!!, 0.0)
    }

    @Test fun manualSelectionAndPointExitCannotInheritTerrainHeight() {
        for ((key, value) in listOf<Pair<String, Any>>("sim_mode" to "JOYSTICK", "is_route_mode" to true,
            "active" to false, "lat" to 1.0, "lng" to -36.0)) {
            val json = point()
            PointHeightBinding.assign(json, 25.0, 904.0)
            json.put(key, value)
            PointHeightBinding.reconcilePatch(json) { 21.0 }
            assertEquals(21.0, json.getDouble("altitude"), 0.0)
            assertFalse(json.has("altitude_source"))
            assertNull(PointHeightBinding.restoredMeters(json, json.getDouble("lat"), json.getDouble("lng")))
        }
        val json = point()
        PointHeightBinding.assign(json, 25.0, 904.0)
        PointHeightBinding.assign(json, -6.0, null)
        assertEquals(-6.0, json.getDouble("altitude"), 0.0)
        assertFalse(json.has("altitude_source"))
    }

    @Test fun automaticSessionChoosesVerticalVariationAndManualSessionKeepsThePreset() {
        val json = point()
        assertFalse(PointHeightBinding.isBound(json))
        PointHeightBinding.assign(json, 25.0, 368.0)
        assertTrue(PointHeightBinding.isBound(JSONObject(json.toString())))
        for ((level, expected) in listOf(0 to 0.0, 1 to 3.0, 2 to 6.0, 3 to 10.0)) {
            assertEquals(expected, PointHeightBinding.variationMeters(json, level, 35), 0.0)
        }
        json.put("sim_mode", "JOYSTICK")
        PointHeightBinding.reconcilePatch(json) { 25.0 }
        assertFalse(PointHeightBinding.isBound(json))
        assertEquals(35.0, PointHeightBinding.variationMeters(json, 2, 35), 0.0)
        json.put("sim_mode", "STILL")
        PointHeightBinding.assign(json, 25.0, null)
        assertFalse(PointHeightBinding.isBound(json))
        assertEquals(35.0, PointHeightBinding.variationMeters(json, 2, 35), 0.0)
    }

    @Test fun invalidOrNonStationaryAutomaticValueIsNeverBound() {
        for (json in listOf(point().put("is_route_mode", true), point().put("active", false),
            point().put("sim_mode", "JOYSTICK"), point(91.0))) {
            PointHeightBinding.assign(json, 25.0, 812.0)
            assertEquals(25.0, json.getDouble("altitude"), 0.0)
            assertFalse(json.has("altitude_source"))
        }
        val json = point()
        PointHeightBinding.assign(json, 25.0, Double.NaN)
        assertFalse(json.has("altitude_source"))
        assertEquals(25.0, json.getDouble("altitude"), 0.0)
    }
}
