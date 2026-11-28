package com.vincenthzr.locationspoofer.utils

import org.json.JSONObject

/** Only an explicitly committed stationary point owns its terrain-derived WGS84 ellipsoid height.
 * Binding coordinates use the same GCJ-02 input space as config lat/lng, not the DEM query space. */
internal object PointHeightBinding {
    private const val SOURCE = "altitude_source"
    private const val TERRAIN = "target_terrain"
    private const val LAT = "altitude_target_gcj_lat"
    private const val LNG = "altitude_target_gcj_lng"

    fun validPosition(lat: Double?, lng: Double?): Boolean =
        lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0

    // Old preferences return the string "0" for missing coordinates. Do not turn that sentinel into
    // a real fix; a genuinely saved point on either axis is confirmed by its active snapshot.
    fun canRestorePosition(json: JSONObject?, lat: Double, lng: Double): Boolean =
        validPosition(lat, lng) && ((lat != 0.0 && lng != 0.0) ||
            (json != null && json.optBoolean("active") && json.optDouble("lat", Double.NaN) == lat &&
                json.optDouble("lng", Double.NaN) == lng))

    private fun isPoint(json: JSONObject): Boolean =
        json.optBoolean("active") && !json.optBoolean("is_route_mode") && json.optString("sim_mode") == "STILL"

    fun assign(json: JSONObject, manualMeters: Double, pointMeters: Double?) {
        val lat = json.optDouble("lat", Double.NaN)
        val lng = json.optDouble("lng", Double.NaN)
        if (isPoint(json) && validPosition(lat, lng) && pointMeters?.isFinite() == true) {
            json.put("altitude", pointMeters).put(SOURCE, TERRAIN).put(LAT, lat).put(LNG, lng)
        } else {
            json.put("altitude", manualMeters)
            json.remove(SOURCE)
            json.remove(LAT)
            json.remove(LNG)
        }
    }

    fun isBound(json: JSONObject?): Boolean = json != null &&
        restoredMeters(json, json.optDouble("lat", Double.NaN), json.optDouble("lng", Double.NaN)) != null

    /** STILL uses half the configured variation, capped by the selected profile's receiver drift. */
    fun variationMeters(json: JSONObject, realismLevel: Int, manualVariation: Int): Double =
        if (isBound(json)) 2 * MotionRealism.Level.fromId(realismLevel).altitudeAmplitudeM else manualVariation.toDouble()

    fun restoredMeters(json: JSONObject?, lat: Double, lng: Double): Double? {
        if (json == null || !isPoint(json) || json.optString(SOURCE) != TERRAIN || !validPosition(lat, lng)) return null
        if (json.optDouble(LAT, Double.NaN) != lat || json.optDouble(LNG, Double.NaN) != lng ||
            json.optDouble("lat", Double.NaN) != lat || json.optDouble("lng", Double.NaN) != lng) return null
        return json.optDouble("altitude", Double.NaN).takeIf { it.isFinite() }
    }

    // Most patches only change unrelated fields. Fall back once, when a formerly bound point moves
    // or switches to joystick/route. Never copy terrain height into the manual altitude preference.
    fun reconcilePatch(json: JSONObject, manualMeters: () -> Double) {
        if (json.optString(SOURCE) == TERRAIN &&
            restoredMeters(json, json.optDouble("lat", Double.NaN), json.optDouble("lng", Double.NaN)) == null
        ) assign(json, manualMeters(), null)
    }
}
