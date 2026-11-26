package com.vincenthzr.locationspoofer

import android.app.Activity
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.util.AtomicFile
import com.vincenthzr.locationspoofer.data.model.RemoteCellRadio
import com.vincenthzr.locationspoofer.data.model.RemoteCellIdentity
import com.vincenthzr.locationspoofer.data.model.RemoteWifiRegion
import com.vincenthzr.locationspoofer.data.db.EnvironmentDao
import com.vincenthzr.locationspoofer.data.repository.RemoteCellRepository
import com.vincenthzr.locationspoofer.data.repository.SettingsRepository
import com.vincenthzr.locationspoofer.utils.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.koin.core.context.GlobalContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.floor

internal fun SystemFilesInstrumentation.verifyOfflineCellIndex(): Bundle = runBlocking {
    val output = Bundle(); val id = UUID.randomUUID().toString()
    val source = File(targetContext.cacheDir, "offline-fixture-$id.sqlite")
    val target = File(targetContext.cacheDir, "offline-index-$id.sqlite")
    var http: OkHttpClient? = null
    try {
        val now = System.currentTimeMillis(); val origin = CoordinateUtils.LatLng(30.0, 113.0)
        val near = GeoMath.destination(origin, 0.0, 100.0); val far = GeoMath.destination(origin, 0.0, 2500.0)
        SQLiteDatabase.openOrCreateDatabase(source, null).use { db ->
            db.version = 1
            db.execSQL("CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
            db.execSQL("CREATE TABLE cells(mcc INTEGER,mnc INTEGER,tac INTEGER,ci INTEGER,lat REAL,lon REAL,updated INTEGER,grid_lat INTEGER,grid_lon INTEGER)")
            db.execSQL("CREATE INDEX cell_grid ON cells(grid_lat,grid_lon)")
            val metadata = mapOf("format" to "locationspoofer-lte-index-v1", "source" to "OpenCellID", "radio" to "LTE",
                "grid_degrees" to "0.01", "mcc" to "460", "rows" to "6", "imported_at" to now.toString(), "source_sha256" to "a".repeat(64))
            metadata.forEach { (key, value) -> db.execSQL("INSERT INTO metadata VALUES(?,?)", arrayOf(key, value)) }
            val rows = listOf(Triple(near, now, 1L), Triple(far, now, 2L), Triple(near, now - 600L * 86_400_000, 3L),
                Triple(near, now + 86_400_000, 4L), Triple(CoordinateUtils.LatLng(0.0, 179.999), now, 5L), Triple(near, now, Long.MAX_VALUE))
            rows.forEach { (point, updated, cell) -> db.execSQL("INSERT INTO cells VALUES(?,?,?,?,?,?,?,?,?)",
                arrayOf(460, 0, 0, cell, point.lat, point.lng, updated, floor(point.lat * 100).toInt(), floor(point.lng * 100).toInt())) }
        }
        val index = OfflineCellSeedIndex(target)
        source.inputStream().use { check(index.install(it).rows == 6) }
        val selected = index.search(30.0, 113.0, RemoteCellRadio.LTE)
        check(selected.candidates.map { it.identity.id } == listOf(1L, 2L))
        check(selected.report.getJSONArray("stages").length() == 3 && selected.report.getInt("online_requests") == 0)
        check(index.search(30.0, 113.0, RemoteCellRadio.LTE).report.getBoolean("from_cache"))
        check(index.search(0.0, -179.999, RemoteCellRadio.LTE).candidates.single().identity.id == 5L)
        check(index.search(30.0, 113.0, RemoteCellRadio.NR).candidates.isEmpty())
        val before = MessageDigest.getInstance("SHA-256").digest(target.readBytes())
        try { "not-a-database".byteInputStream().use { index.install(it) }; error("Invalid import accepted") }
        catch (_: android.database.SQLException) { }
        check(before.contentEquals(MessageDigest.getInstance("SHA-256").digest(target.readBytes())))
        val onlineCalls = AtomicInteger()
        http = OkHttpClient.Builder().addInterceptor { onlineCalls.incrementAndGet(); throw IOException("No online query allowed") }.build()
        val client = AppleCellClient(http, OpenCellSearchClient(http), index)
        val seed = checkNotNull(client.findSeed(30.0, 113.0, "", RemoteCellRadio.LTE))
        check(seed.id == 1L && seed.mnc == 0 && seed.tac == 0)
        check(client.findSeed(30.0, 113.0, "configured", RemoteCellRadio.LTE, selected.candidates.map { it.identity }.toSet()) == null)
        check(onlineCalls.get() == 0)
        output.putString("indexed_radii_and_original_coordinates", "PASS")
        output.putString("stale_future_invalid_identity_and_nr_filter", "PASS")
        output.putString("dateline_wrap", "PASS")
        output.putString("cache_reuse", "PASS")
        output.putString("invalid_import_preserves_working_index", "PASS")
        output.putString("offline_seed_without_api_key_or_online_quota", "PASS")
        output.putString("exhausted_local_candidates_do_not_rescan_online", "PASS")
        output.putString("result", "PASS")
    } catch (error: Exception) { output.putString("result", "FAIL: ${error.stackTraceToString()}") }
    finally {
        http?.connectionPool?.evictAll(); http?.dispatcher?.executorService?.shutdown()
        AtomicFile(source).delete(); AtomicFile(target).delete()
    }
    output
}

/** Installs the staged country index once; live lookups neither import environment records nor publish mock output. */
internal fun SystemFilesInstrumentation.diagnoseOfflineCells(arguments: Bundle?): Bundle = runBlocking {
    val output = Bundle(); val report = JSONObject(); val checks = JSONArray(); var activity: Activity? = null
    try {
        activity = startActivitySync(Intent(targetContext, LookupDiagnosticsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val prefs = targetContext.getSharedPreferences("framework_config_state", 0)
        val original = checkNotNull(prefs.getString("desired_config", null)); val config = JSONObject(original)
        val settings = targetContext.getSharedPreferences("app_settings", 0)
        val inputLat = arguments?.getString("lookup-lat")?.toDoubleOrNull()
        val inputLon = arguments?.getString("lookup-lon")?.toDoubleOrNull()
        require((inputLat == null) == (inputLon == null))
        val point = if (inputLat != null && inputLon != null) CoordinateUtils.gcj02ToWgs84(inputLat, inputLon)
            else if (config.optBoolean("active")) CoordinateUtils.LatLng(config.getDouble("wgs84_lat"), config.getDouble("wgs84_lng"))
            else CoordinateUtils.gcj02ToWgs84(checkNotNull(settings.all["last_spoofed_lat"]).toString().toDouble(),
                checkNotNull(settings.all["last_spoofed_lng"]).toString().toDouble())
        require(point.lat in -85.0..85.0 && point.lng in -180.0..180.0 && (point.lat != 0.0 || point.lng != 0.0))
        val koin = withTimeout(20_000) {
            while (GlobalContext.getOrNull()?.getOrNull<OfflineCellSeedIndex>() == null) delay(100)
            GlobalContext.get()
        }
        val index = koin.get<OfflineCellSeedIndex>()
        val staged = File(targetContext.cacheDir, "offline_lte_seed_import.sqlite")
        if (staged.exists()) { staged.inputStream().use { index.install(it) }; check(staged.delete()) }
        val info = checkNotNull(index.status())
        arguments?.getString("source-sha")?.let { check(info.sourceSha256 == it) }
        val pool = index.search(point.lat, point.lng, RemoteCellRadio.LTE)
        report.put("latitude", point.lat).put("longitude", point.lng).put("index", pool.report)
        sendStatus(1, Bundle().apply { putString("progress", "Offline LTE search completed: ${pool.candidates.size} candidates; checking WLOC") })
        val client = koin.get<AppleCellClient>(); val online = koin.get<OpenCellSearchClient>()
        val before = online.lastReport; val tried = linkedSetOf<RemoteCellIdentity>()
        val region = if (point.lat in 18.0..54.0 && point.lng in 73.0..135.0) RemoteWifiRegion.MAINLAND_CHINA else RemoteWifiRegion.GLOBAL
        withTimeout(60_000) {
            for (attempt in 0 until 3) {
                val seed = client.findSeed(point.lat, point.lng, "", RemoteCellRadio.LTE, tried) ?: break
                tried += seed
                val candidate = pool.candidates.first { it.identity == seed }
                val entry = JSONObject().put("seed_mcc", seed.mcc).put("seed_mnc", seed.mnc).put("seed_distance_meters", candidate.distanceMeters)
                try {
                    val result = client.query(point.lat, point.lng, region, seed)
                    check(result.points.all { it.distanceMeters <= 10_000 && it.identity.mcc == seed.mcc && it.identity.mnc == seed.mnc })
                    entry.put("source", "Apple WLOC").put("records", result.points.size)
                        .put("nearest_meters", result.points.minOf { it.distanceMeters }).put("farthest_meters", result.points.maxOf { it.distanceMeters })
                    checks.put(entry); break
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    entry.put("error", error.javaClass.simpleName); checks.put(entry)
                    if (error !is RemoteCellNoCoverageException) break
                }
            }
        }
        if (arguments?.getString("lookup-auto") == "true") {
            val cache = File(targetContext.cacheDir, "offline-auto-${UUID.randomUUID()}.json")
            try {
                val repository = RemoteCellRepository(client, koin.get<EnvironmentDao>(), koin.get<SettingsRepository>(), cache)
                val snapshot = withTimeout(60_000) { repository.query(point.lat, point.lng, region, preferredRadio = RemoteCellRadio.LTE) }
                check(repository.loadLatest() == snapshot)
                report.put("automatic_repository", JSONObject().put("source", "Apple WLOC").put("records", snapshot.points.size)
                    .put("nearest_meters", snapshot.points.minOf { it.distanceMeters })
                    .put("farthest_meters", snapshot.points.maxOf { it.distanceMeters }).put("isolated_cache_round_trip", true))
            } catch (error: CancellationException) { throw error }
            catch (error: RemoteCellNoCoverageException) { report.put("automatic_repository", JSONObject().put("error", error.javaClass.simpleName)) }
            catch (error: RemoteCellNoSeedException) { report.put("automatic_repository", JSONObject().put("error", error.javaClass.simpleName)) }
            finally { AtomicFile(cache).delete() }
        }
        check(online.lastReport === before); check(original == prefs.getString("desired_config", null))
        report.put("wloc_checks", checks).put("online_cell_queries", 0).put("desired_config_unchanged", true)
        output.putString("result", "PASS")
    } catch (error: Exception) { output.putString("result", "FAIL: ${error.stackTraceToString()}") }
    finally {
        report.put("wloc_checks", checks); output.putString("diagnosis", report.toString())
        activity?.let { runOnMainSync { it.finish() } }
    }
    output
}
