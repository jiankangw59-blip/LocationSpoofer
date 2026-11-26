package com.vincenthzr.locationspoofer

import android.os.Bundle
import android.util.AtomicFile
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import com.vincenthzr.locationspoofer.data.db.AppDatabase
import com.vincenthzr.locationspoofer.data.repository.*
import com.vincenthzr.locationspoofer.utils.*
import com.vincenthzr.locationspoofer.viewmodel.RemoteWifiViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Wrong-point caches must neither change the query input nor be importable under another point. */
internal fun SystemFilesInstrumentation.verifyRemotePreviewTarget(): Bundle = runBlocking {
    val output = Bundle(); val id = UUID.randomUUID().toString()
    val wifiFile = File(targetContext.cacheDir, "preview-wifi-$id.json")
    val cellFile = File(targetContext.cacheDir, "preview-cell-$id.json")
    val pref = "preview-target-$id"
    val context = object : android.content.ContextWrapper(targetContext) {
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(pref, mode)
    }
    val db = Room.inMemoryDatabaseBuilder(targetContext, AppDatabase::class.java).build()
    val store = ViewModelStore()
    try {
        val lat = 30.6522; val lon = 113.1427
        val identity = JSONObject().put("radio", "LTE").put("mcc", 460).put("mnc", 0).put("tac", 321).put("id", 123456)
        wifiFile.writeText(JSONObject().put("version", 3).put("source", "Apple WLOC").put("source_type", "APPLE_WLOC")
            .put("coordinate_system", "WGS84").put("latitude", lat).put("longitude", lon).put("region", "MAINLAND_CHINA")
            .put("tile_key", 98961500L).put("fetched_at", System.currentTimeMillis()).put("points", JSONArray().put(JSONObject()
                .put("bssid", "02:00:00:00:00:01").put("latitude", lat).put("longitude", lon))).toString())
        cellFile.writeText(JSONObject().put("version", 1).put("coordinate_system", "WGS84")
            .put("latitude", lat).put("longitude", lon).put("region", "MAINLAND_CHINA").put("fetched_at", System.currentTimeMillis())
            .put("seed", identity).put("points", JSONArray().put(JSONObject(identity.toString()).put("latitude", lat).put("longitude", lon))).toString())
        val wifi = RemoteWifiRepository(AppleWifiTileClient(), wifiFile)
        val cells = RemoteCellRepository(AppleCellClient(), db.environmentDao(), SettingsRepository(SettingsManager(context)), cellFile)
        lateinit var changed: RemoteWifiViewModel
        lateinit var cached: RemoteWifiViewModel
        runOnMainSync {
            changed = RemoteWifiViewModel(wifi, cells, RemoteEnvironmentImporter(db)); store.put("changed", changed)
            changed.initialize(10.0, 10.0)
        }
        val state = withTimeout(10_000) { changed.state.first { !it.busy } }
        check(state.latitude.toDouble() == 10.0 && state.longitude.toDouble() == 10.0)
        check(state.snapshot == null && state.cellSnapshot == null && !state.fromCache && !state.cellFromCache)
        runOnMainSync {
            cached = RemoteWifiViewModel(wifi, cells, RemoteEnvironmentImporter(db)); store.put("cached", cached)
            cached.initialize(null, null)
        }
        val loaded = withTimeout(10_000) { cached.state.first { !it.busy } }
        check(loaded.snapshot != null && loaded.cellSnapshot != null)
        var imported = false
        runOnMainSync {
            cached.setLatitude("20.0")
            cached.importWifi { imported = true }; cached.importCells { imported = true }
        }
        check(cached.state.value.snapshot == null && cached.state.value.cellSnapshot == null)
        check(!imported && db.environmentDao().getAllLocations().isEmpty())
        output.putString("wrong_point_cache_does_not_override_target", "PASS")
        output.putString("cache_without_explicit_target_remains_available", "PASS")
        output.putString("editing_target_invalidates_importable_records", "PASS")
        output.putString("result", "PASS")
    } catch (error: Exception) { output.putString("result", "FAIL: ${error.stackTraceToString()}") }
    finally {
        runOnMainSync { store.clear() }; db.close()
        AtomicFile(wifiFile).delete(); AtomicFile(cellFile).delete(); targetContext.deleteSharedPreferences(pref)
    }
    output
}
