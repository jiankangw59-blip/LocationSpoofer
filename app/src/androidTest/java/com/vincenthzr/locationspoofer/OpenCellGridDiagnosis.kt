package com.vincenthzr.locationspoofer

import android.os.Bundle
import com.vincenthzr.locationspoofer.data.model.RemoteWifiRegion
import com.vincenthzr.locationspoofer.data.repository.SettingsRepository
import com.vincenthzr.locationspoofer.utils.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.koin.core.context.GlobalContext

/** Execute the installed production search; no imports, settings writes, or simulation publications. */
internal fun SystemFilesInstrumentation.diagnoseOpenCellGrid(): Bundle = runBlocking {
    val output = Bundle()
    try {
        val prefs = targetContext.getSharedPreferences("framework_config_state", 0)
        val original = checkNotNull(prefs.getString("desired_config", null))
        val config = JSONObject(original)
        val lat = config.getDouble("wgs84_lat"); val lon = config.getDouble("wgs84_lng")
        val koin = withTimeout(20_000) {
            while (GlobalContext.getOrNull()?.getOrNull<OpenCellSearchClient>() == null) delay(100)
            GlobalContext.get()
        }
        val search = koin.get<OpenCellSearchClient>()
        val settings = koin.get<SettingsRepository>()
        val report = JSONObject()
        try {
            val result = search.search(lat, lon, settings.getOpencellidApiToken(), forceRefresh = true)
            report.put("search", result.report.toJson())
            val fieldNames = result.records.flatMap { JSONObject(it.sourceJson).keys().asSequence().toList() }.distinct().sorted()
            report.put("record_fields", JSONArray(fieldNames))
            report.put("usable_radios", JSONObject(result.records.groupingBy { it.radio }.eachCount()))
            report.put("apple_seed_candidates", result.records.count { it.appleSeed() != null })
            if (result.records.isNotEmpty()) {
                report.put("nearest_meters", result.records.minOf { it.distanceMeters })
                report.put("farthest_meters", result.records.maxOf { it.distanceMeters })
            }
            // Exercise the same discovery cache without repeating the grid or exposing cell identifiers.
            search.search(lat, lon, settings.getOpencellidApiToken()).also {
                check(it.report.fromCache); check(it.records == result.records)
                report.put("repeat_reuses_cache", true)
            }
            val expanded = JSONArray()
            val region = if (lat in 18.0..54.0 && lon in 73.0..135.0) RemoteWifiRegion.MAINLAND_CHINA else RemoteWifiRegion.GLOBAL
            val finished = withTimeoutOrNull(20_000) {
                for (record in result.records.filter { it.appleSeed() != null }.take(3)) {
                    val item = JSONObject().put("radio", record.radio).put("freshness", record.freshness.name)
                        .put("seed_distance_meters", record.distanceMeters)
                    try {
                        val data = koin.get<AppleCellClient>().query(lat, lon, region, record.appleSeed()!!)
                        item.put("status", "OK").put("records", data.points.size)
                        expanded.put(item); break
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) {
                        item.put("status", error.javaClass.simpleName)
                        (error as? RemoteWifiQueryException)?.let { item.put("http", it.httpStatus) }
                        expanded.put(item)
                        if (error !is RemoteCellNoCoverageException) break
                    }
                }
                true
            }
            report.put("apple_expansion", expanded).put("apple_budget_expired", finished != true)
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            report.put("search", search.lastReport?.toJson()).put("error", error.javaClass.simpleName)
            (error as? OpenCellLookupException)?.let { report.put("http", it.httpStatus).put("api_code", it.apiCode) }
        }
        check(original == prefs.getString("desired_config", null)) { "Active target changed during diagnosis" }
        report.put("desired_config_unchanged", true)
        output.putString("diagnosis", report.toString()); output.putString("result", "PASS")
    } catch (error: Exception) { output.putString("result", "FAIL: ${error.javaClass.simpleName}") }
    output
}
