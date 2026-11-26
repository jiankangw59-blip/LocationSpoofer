package com.vincenthzr.locationspoofer

import android.os.Bundle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import com.vincenthzr.locationspoofer.data.db.AppDatabase
import com.vincenthzr.locationspoofer.data.model.RemoteWifiRegion
import com.vincenthzr.locationspoofer.data.model.RemoteWifiMetadata
import com.vincenthzr.locationspoofer.data.model.RemoteCellIdentity
import com.vincenthzr.locationspoofer.data.model.RemoteCellRadio
import com.vincenthzr.locationspoofer.data.model.RemoteCellPoint
import com.vincenthzr.locationspoofer.data.model.RemoteCellSnapshot
import com.vincenthzr.locationspoofer.data.repository.*
import com.vincenthzr.locationspoofer.utils.*
import com.vincenthzr.locationspoofer.viewmodel.MainViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.koin.core.context.GlobalContext
import java.security.MessageDigest

/** Temporary active payload targets only our disposable probe; the original remote snapshot is restored byte-for-byte. */
internal fun SystemFilesInstrumentation.remoteEnvironmentOutput(
    restore: Boolean, nrFixture: Boolean = false, metadataFixture: Boolean = false, runId: String?
): Bundle = runBlocking {
    val output = Bundle()
    if (restore) return@runBlocking withFixtureConfig(runId) { it.restore() }
    val baseline = withFixtureConfig(runId) { it.read() }
    check(!baseline.payload().optBoolean("active")) { "Preserve the active user simulation" }
    val store = ViewModelStore()
    val database = Room.inMemoryDatabaseBuilder(targetContext, AppDatabase::class.java).build()
    try {
        val koin = GlobalContext.get()
        val preview = checkNotNull(koin.get<RemoteWifiRepository>().loadLatest())
        val wifi = if (metadataFixture) preview.copy(points = preview.points.mapIndexed { index, point ->
            point.copy(metadata = if (index == 0) RemoteWifiMetadata("\u6d4b\u8bd5-WiFi", 5180, 36, null, System.currentTimeMillis()) else null)
        }) else preview
        val cells = checkNotNull(koin.get<RemoteCellRepository>().loadLatest())
        check(cells.region == wifi.region && GeoMath.distance(CoordinateUtils.LatLng(cells.latitude, cells.longitude),
            CoordinateUtils.LatLng(wifi.latitude, wifi.longitude)) <= 2.0) { "Cached cell preview belongs to another target" }
        val importer = RemoteEnvironmentImporter(database)
        val id = importer.importWifi(wifi)
        check(importer.importCells(cells) == id)
        val nrIdentities = listOf(
            RemoteCellIdentity(RemoteCellRadio.NR, 460, 0, 0, 4_294_967_297L),
            RemoteCellIdentity(RemoteCellRadio.NR, 460, 0, 16_777_215, 68_719_476_735L)
        )
        if (nrFixture) {
            importer.importCells(RemoteCellSnapshot(wifi.latitude, wifi.longitude, wifi.region,
                nrIdentities.first(), System.currentTimeMillis(), nrIdentities.map {
                    RemoteCellPoint(it, wifi.latitude, wifi.longitude, 0.0)
                }))
        }
        val record = checkNotNull(database.environmentDao().getCompleteLocationById(id))
        lateinit var viewModel: MainViewModel
        runOnMainSync { viewModel = koin.get(); store.put("remote-output", viewModel) }
        withTimeout(20_000) { viewModel.uiState.first { !it.isInitializing } }
        val serialize = Class.forName("com.vincenthzr.locationspoofer.viewmodel.MainViewModelDataIOKt")
            .getDeclaredMethod("locationToJson", MainViewModel::class.java, List::class.java, Double::class.javaPrimitiveType, Double::class.javaPrimitiveType)
        @Suppress("UNCHECKED_CAST")
        val jsons = serialize.invoke(null, viewModel, listOf(record), record.location.lat, record.location.lng) as Triple<String, String, String>
        val serializedCells = JSONArray(jsons.second)
        check((0 until serializedCells.length()).count { serializedCells.getJSONObject(it).optBoolean("isRegistered") } == 1)
        check(record.cells.all { !it.locationCell.isRegistered }) { "Simulation attachment changed source observations" }
        val payload = baseline.payload().apply {
            put("active", true); put("system_hook_global_mode", false)
            put("system_hook_packages", JSONArray().put("com.codex.remoteenvironment.probe"))
            put("lat", record.location.lat); put("lng", record.location.lng)
            put("wgs84_lat", wifi.latitude); put("wgs84_lng", wifi.longitude)
            put("mock_wifi", true); put("mock_cell", true); put("mock_bluetooth", false)
            put("enable_jitter", true)
            put("wifi_json", JSONObject(jsons.first)); put("cell_json", JSONArray(jsons.second)); put("bluetooth_json", JSONArray())
        }
        if (metadataFixture) {
            val projectedWifi = payload.getJSONObject("wifi_json")
            val nearby = projectedWifi.getJSONArray("nearbyWifi")
            val connected = (0 until nearby.length()).map { nearby.getJSONObject(it) }.first { it.optString("ssid").isNotEmpty() }
            projectedWifi.put("isConnected", true).put("connectedWifi", JSONObject(connected.toString()))
        }
        withFixtureConfig(runId) { it.prepare(baseline, payload) }
        val hash = MessageDigest.getInstance("SHA-256").digest(wifi.points.map { it.bssid }.sorted().joinToString(",").toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        output.putInt("expected_wifi_records", wifi.points.size)
        output.putInt("source_cell_pool_records", cells.points.size + if (nrFixture) nrIdentities.size else 0)
        output.putInt("expected_cell_records", serializedCells.length())
        output.putDouble("expected_lat_wgs84", wifi.latitude)
        output.putDouble("expected_lng_wgs84", wifi.longitude)
        if (nrFixture) {
            val nrValues = nrIdentities.map { "${it.tac}/${it.id}" }.sorted().joinToString(",")
            output.putString("expected_nr_identity_sha256", MessageDigest.getInstance("SHA-256")
                .digest(nrValues.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) })
            output.putInt("expected_nr_records", nrIdentities.size)
        }
        output.putString("expected_wifi_bssid_sha256", hash)
        val wifiMetadata = record.wifis.map { "${it.device.bssid.lowercase()}\u0000${it.device.ssid}\u0000${it.device.frequency}\u0000${it.device.capabilities}" }
            .sorted().joinToString(",")
        output.putString("expected_wifi_metadata_sha256", MessageDigest.getInstance("SHA-256")
            .digest(wifiMetadata.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) })
        output.putBoolean("synthetic_wifi_metadata_fixture", metadataFixture)
        output.putString("environment_source", "saved_real_query_previews")
        output.putString("result", "PASS")
    } finally {
        runOnMainSync { store.clear() }
        database.close()
    }
    output
}
