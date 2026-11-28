package com.vincenthzr.locationspoofer

import android.os.Bundle
import com.vincenthzr.locationspoofer.utils.*
import com.vincenthzr.locationspoofer.utils.CoordinateUtils.LatLng
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

internal fun SystemFilesInstrumentation.locationNoiseRegression(operation: String, samples: String?, runId: String?): Bundle = runBlocking {
    val output = Bundle()
    val base = LatLng(30.6544979, 113.1366294)
    fun bleFixtures() = JSONArray().put(JSONObject().put("address", "DA:C0:DE:00:00:01")
        .put("name", "CodexBT").put("rssi", -61).put("scanRecordHex", "0201060809436f6465784254"))
    try {
        when (operation) {
            "location-noise-restore" -> return@runBlocking withFixtureConfig(runId) { it.restore() }
            "gnss-integrity-pause", "gnss-integrity-resume", "gnss-integrity-exclude", "gnss-integrity-include",
            "ble-output-empty", "ble-output-disabled", "ble-output-filled" -> withFixtureConfig(runId) { fixture ->
                fixture.update {
                    if (operation.startsWith("ble-output-")) {
                        put("mock_bluetooth", operation != "ble-output-disabled")
                        put("bluetooth_json", if (operation.endsWith("filled")) bleFixtures() else JSONArray())
                    } else if (operation.endsWith("include") || operation.endsWith("exclude")) {
                        put("active", true).put("system_hook_packages", JSONArray().put(
                            if (operation.endsWith("include")) "com.codex.remoteenvironment.probe" else "com.codex.remoteenvironment.excluded"))
                    } else put("active", operation.endsWith("resume"))
                }
            }
            "location-noise-prepare-on", "location-noise-prepare-off", "gnss-integrity-prepare", "ble-output-prepare" -> withFixtureConfig(runId) { fixture ->
                val baseline = fixture.read()
                val payload = baseline.payload()
                val gcj = CoordinateUtils.wgs84ToGcj02(base.lat, base.lng)
                payload.put("active", true).put("system_hook_global_mode", false)
                    .put("system_hook_packages", JSONArray().put("com.codex.remoteenvironment.probe"))
                    .put("lat", gcj.lat).put("lng", gcj.lng).put("wgs84_lat", base.lat).put("wgs84_lng", base.lng)
                    .put("enable_jitter", operation.endsWith("on"))
                    .put("is_route_mode", false).put("sim_mode", if (operation == "gnss-integrity-prepare") "STILL" else "STATIC")
                    .put("speed_m_s", 0.0).put("sim_bearing", 0.0)
                    .put("altitude", 25.0).put("altitude_variation_m", if (operation == "gnss-integrity-prepare") 35 else 0)
                    .put("realism_level", if (operation == "gnss-integrity-prepare") 2 else 0)
                    .put("satellite_count", if (operation == "gnss-integrity-prepare") 24 else 10)
                    .put("mock_wifi", false).put("mock_cell", false).put("mock_bluetooth", operation == "ble-output-prepare")
                if (operation == "ble-output-prepare") payload.put("bluetooth_json", bleFixtures())
                fixture.prepare(baseline, payload)
            }
            "location-noise-validate-on", "location-noise-validate-off" -> {
                val enabled = operation.endsWith("on")
                require(samples != null && samples.length <= 65_536)
                val array = JSONArray(samples)
                check(array.length() in 8..100)
                val counts = mutableMapOf<String, Int>()
                val times = mutableMapOf<String, Long>()
                val positions = mutableMapOf<String, MutableSet<LatLng>>()
                var maxError = 0.0
                var maxOffset = 0.0
                for (index in 0 until array.length()) {
                    val fix = array.getJSONObject(index)
                    val provider = fix.getString("provider")
                    val time = fix.getLong("time")
                    check(time >= (times[provider] ?: 0L) && abs(System.currentTimeMillis() - time) < 90_000)
                    val position = LatLng(fix.getDouble("latitude"), fix.getDouble("longitude"))
                    val expected = LocationNoise.sample(base, time, enabled)
                    val error = GeoMath.distance(position, LatLng(expected.latitude, expected.longitude))
                    val offset = GeoMath.distance(base, position)
                    check(error < if (enabled) 0.08 else 0.01) { "$provider fix disagrees with shared noise: $error m" }
                    check(abs(expected.accuracyM - fix.getDouble("accuracy")) < 0.05) { "Accuracy disagrees with shared noise" }
                    check(offset < fix.getDouble("accuracy") && offset < 1.21)
                    check(!fix.getBoolean("mock") && fix.getDouble("speed") == 0.0)
                    counts[provider] = (counts[provider] ?: 0) + 1
                    times[provider] = time
                    positions.getOrPut(provider) { mutableSetOf() }.add(position)
                    maxError = maxOf(maxError, error); maxOffset = maxOf(maxOffset, offset)
                }
                check((counts["gps"] ?: 0) >= 4 && (counts["network"] ?: 0) >= 4)
                positions.values.forEach { check(if (enabled) it.size > 1 else it.size == 1) { "Noise toggle was ignored" } }
                output.putString("provider_counts", counts.toString())
                output.putDouble("max_model_error_m", maxError)
                output.putDouble("max_target_offset_m", maxOffset)
                output.putBoolean("noise_enabled", enabled)
            }
            "gnss-integrity-validate-altitude" -> {
                require(samples != null && samples.length <= 65_536)
                val array = JSONArray(samples)
                check(array.length() in 4..40)
                var maximumError = 0.0
                for (index in 0 until array.length()) {
                    val fix = array.getJSONObject(index)
                    val expected = AltitudeModel.stationaryAltitude(25.0, 35.0, 3.0, fix.getLong("time"))
                    maximumError = maxOf(maximumError, abs(expected - fix.getDouble("altitude")))
                }
                output.putDouble("max_altitude_error_m", maximumError)
                check(maximumError < 0.05) { "An output path ignored the shared altitude model" }
            }
            else -> error("Unknown operation")
        }
        output.putString("result", "PASS")
    } catch (error: Throwable) { output.putString("result", "FAIL: ${error.stackTraceToString()}") }
    output
}
