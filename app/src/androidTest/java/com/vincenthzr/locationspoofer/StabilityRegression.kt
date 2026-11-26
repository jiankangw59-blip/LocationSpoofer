package com.vincenthzr.locationspoofer

import android.os.Bundle
import android.os.SystemClock
import com.vincenthzr.locationspoofer.utils.CoordinateUtils
import com.vincenthzr.locationspoofer.utils.XposedModuleStatus
import io.github.libxposed.service.HotReloadResult
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** Disjoint A/B identities stay in the disposable reader's fixture. */
internal fun SystemFilesInstrumentation.stabilityRegression(operation: String, runId: String?): Bundle = runBlocking {
    val output = Bundle()
    if (operation == "stability-restore") return@runBlocking withFixtureConfig(runId) { it.restore() }
    val service = withTimeout(20_000) {
        while (XposedModuleStatus.mService == null) delay(100)
        checkNotNull(XposedModuleStatus.mService)
    }
    if (operation == "stability-prepare") {
        val prepared = remoteEnvironmentOutput(false, false, true, runId)
        check(prepared.getString("result") == "PASS")
    }
    val trace = JSONArray()
    suspend fun publish(label: String, active: Boolean = true) {
        val begin = SystemClock.elapsedRealtimeNanos()
        withFixtureConfig(runId) { fixture ->
            fixture.update {
                val lat = optDouble("stability_base_lat", getDouble("wgs84_lat"))
                val lng = optDouble("stability_base_lng", getDouble("wgs84_lng"))
                val index = if (label == "A") 0 else 1
                val wgsLat = lat + index * 0.02
                val wgsLng = lng + index * 0.02
                val gcj = CoordinateUtils.wgs84ToGcj02(wgsLat, wgsLng)
                val wifi = JSONArray()
                repeat(4) { number ->
                    wifi.put(JSONObject().put("bssid", "02:ca:de:${if (index == 0) "a0" else "b0"}:00:%02x".format(number))
                        .put("ssid", "CodexFixture-$label-$number").put("frequency", 2412 + number * 5)
                        .put("capabilities", "[ESS]").put("level", -55 - number * 3))
                }
                val cells = JSONArray()
                repeat(6) { number ->
                    cells.put(JSONObject().put("type", "LTE").put("mcc", 460).put("mnc", 0)
                        .put("tac", 12000 + index).put("ci", (index + 1) * 100000 + number)
                        .put("pci", number).put("dbm", -80 - number * 3).put("distance_m", number * 100.0)
                        .put("isRegistered", number == 0))
                }
                put("stability_base_lat", lat).put("stability_base_lng", lng)
                put("stability_fixture", label).put("active", active)
                put("lat", gcj.lat).put("lng", gcj.lng).put("wgs84_lat", wgsLat).put("wgs84_lng", wgsLng)
                put("mock_bluetooth", false).put("bluetooth_json", JSONArray()).put("cell_json", cells)
                put("wifi_json", JSONObject().put("nearbyWifi", wifi).put("isConnected", true).put("connectedWifi", wifi.getJSONObject(0)))
                if (operation == "stability-prepare") {
                    output.putDouble("a_lat", lat)
                    output.putDouble("a_lng", lng)
                }
            }
        }
        trace.put(JSONObject().put("label", label).put("active", active).put("begin_ns", begin)
            .put("end_ns", SystemClock.elapsedRealtimeNanos()))
    }
    when (operation) {
        "stability-prepare", "stability-stop" -> publish("A", false)
        "stability-a" -> publish("A")
        "stability-b" -> publish("B")
        "stability-burst" -> {
            val scope = service.scope.sorted()
            val pids = service.runningTargets.associate { it.processName to it.pid }
            check(pids.keys == setOf("system", "com.android.phone", "com.android.bluetooth"))
            coroutineScope {
                val changes = launch(Dispatchers.Default) {
                    repeat(50) { index -> publish(if (index % 2 == 0) "B" else "A"); delay(200) }
                }
                delay(500)
                for (target in service.runningTargets) {
                    val completed = CompletableDeferred<HotReloadResult>()
                    service.hotReloadModule(target, null) { _, result -> completed.complete(result) }
                    check(withTimeout(30_000) { completed.await() }.status() == HotReloadResult.Status.SUCCEEDED)
                }
                changes.join()
            }
            check(service.scope.sorted() == scope && service.runningTargets.associate { it.processName to it.pid } == pids)
            publish("A")
        }
        else -> error("Unknown stability operation")
    }
    output.putString("publications", trace.toString())
    output.putString("result", "PASS")
    output
}
