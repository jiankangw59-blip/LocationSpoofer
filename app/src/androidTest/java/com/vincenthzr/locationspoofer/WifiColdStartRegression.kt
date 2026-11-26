package com.vincenthzr.locationspoofer

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.AtomicFile
import com.vincenthzr.locationspoofer.data.repository.*
import com.vincenthzr.locationspoofer.data.model.*
import com.vincenthzr.locationspoofer.utils.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.UUID

/** Runs installed production lookup code and real protocol controls without importing or publishing records. */
internal fun SystemFilesInstrumentation.verifyWifiColdStart(arguments: Bundle? = null): Bundle = runBlocking {
    val output = Bundle(); var activity: Activity? = null
    val temporary = mutableListOf<File>()
    try {
        activity = startActivitySync(Intent(targetContext, LookupDiagnosticsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val transport = targetContext.getSharedPreferences("framework_config_state", 0)
        val original = checkNotNull(transport.getString("desired_config", null))
        val config = JSONObject(original)
        val settings = targetContext.getSharedPreferences("app_settings", 0)
        var source = "ACTIVE_CONFIG"
        val inputLat = arguments?.getString("lookup-lat")?.toDoubleOrNull()
        val inputLon = arguments?.getString("lookup-lon")?.toDoubleOrNull()
        require((inputLat == null) == (inputLon == null))
        val coordinateSystem = arguments?.getString("lookup-crs") ?: "GCJ02"
        require(coordinateSystem in setOf("GCJ02", "WGS84"))
        val target = if (inputLat != null && inputLon != null) {
            require(inputLat in -85.0..85.0 && inputLon in -180.0..180.0)
            source = "EXPLICIT_READ_ONLY_${coordinateSystem}"
            if (coordinateSystem == "GCJ02") CoordinateUtils.gcj02ToWgs84(inputLat, inputLon)
            else CoordinateUtils.LatLng(inputLat, inputLon)
        } else if (config.optBoolean("active")) CoordinateUtils.LatLng(config.getDouble("wgs84_lat"), config.getDouble("wgs84_lng"))
            else {
                source = "LAST_SPOOFED_TARGET"
                fun saved(key: String) = settings.all[key]?.toString()?.toDoubleOrNull()
                val lat = saved("last_spoofed_lat") ?: saved("last_map_lat") ?: error("No saved target")
                val lon = saved("last_spoofed_lng") ?: saved("last_map_lng") ?: error("No saved target")
                require(lat in -85.0..85.0 && lon in -180.0..180.0 && (lat != 0.0 || lon != 0.0))
                CoordinateUtils.gcj02ToWgs84(lat, lon)
            }
        val region = if (target.lat in 18.0..54.0 && target.lng in 73.0..135.0) RemoteWifiRegion.MAINLAND_CHINA else RemoteWifiRegion.GLOBAL
        val koin = withTimeout(20_000) {
            while (GlobalContext.getOrNull()?.getOrNull<AppleWifiDiscovery>() == null) delay(100)
            GlobalContext.get()
        }
        val proxy = koin.get<FeedMeProxyLeaseProvider>()
        val proxyBefore = proxy.status()
        val discovery = koin.get<AppleWifiDiscovery>()
        val report = JSONObject().put("target_source", source).put("simulation_active", config.optBoolean("active"))
            .put("latitude", target.lat).put("longitude", target.lng)
        if (inputLat != null && inputLon != null) {
            val probes = JSONArray()
            val codecType = Class.forName("com.vincenthzr.locationspoofer.utils.AppleWifiTileCodec")
            val codec = codecType.getField("INSTANCE").get(null)
            val tileKey = codecType.getDeclaredMethod("tileKey", Double::class.javaPrimitiveType, Double::class.javaPrimitiveType)
            val decode = codecType.getDeclaredMethod("decode", ByteArray::class.java, Double::class.javaPrimitiveType,
                Double::class.javaPrimitiveType, Double::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            val http = okhttp3.OkHttpClient.Builder().callTimeout(12, java.util.concurrent.TimeUnit.SECONDS).build()
            try {
                withContext(Dispatchers.IO) {
                    for (host in listOf("gspe85-cn-ssl.ls.apple.com", "gspe85-ssl.ls.apple.com")) {
                        val probe = JSONObject().put("host", host)
                        try {
                            val key = tileKey.invoke(codec, target.lat, target.lng) as Long
                            val request = okhttp3.Request.Builder().url("https://$host/wifi_request_tile")
                                .header("Accept", "*/*").header("User-Agent", "geod/1 CFNetwork/1496.0.7 Darwin/23.5.0")
                                .header("X-os-version", "17.5.21F79").header("X-tilekey", key.toString()).build()
                            http.newCall(request).execute().use { response ->
                                val bytes = response.peekBody(2L * 1024 * 1024).bytes()
                                probe.put("tile", key).put("http", response.code).put("body_bytes", bytes.size)
                                if (response.isSuccessful) {
                                    @Suppress("UNCHECKED_CAST")
                                    val points = decode.invoke(codec, bytes, target.lat, target.lng, 12_000.0, 1000) as List<RemoteWifiPoint>
                                    probe.put("positions_within_12km", points.size)
                                        .put("positions_within_2km", points.count { it.distanceMeters <= 2000 })
                                        .put("positions_within_300m", points.count { it.distanceMeters <= 300 })
                                }
                            }
                        } catch (error: CancellationException) { throw error }
                        catch (error: Exception) { probe.put("error", error.javaClass.simpleName) }
                        probes.put(probe)
                    }
                }
            } finally { http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
            report.put("center_tile_region_controls", probes)
        }
        try {
            val result = discovery.query(target.lat, target.lng, region)
            check(result.points.all { it.distanceMeters <= 300.0 })
            report.put("current_wifi", JSONObject().put("source", result.source.name).put("records", result.points.size)
                .put("nearest_meters", result.points.minOf { it.distanceMeters })
                .put("farthest_meters", result.points.maxOf { it.distanceMeters }).put("fetched_at", result.fetchedAt))
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            report.put("current_wifi", JSONObject().put("error", error.javaClass.simpleName))
        }
        report.put("current_search", discovery.lastReport?.toJson())
        sendStatus(1, Bundle().apply { putString("progress", "Current Wi-Fi lookup completed; checking independent controls") })

        // Use known recorded coordinates for an independent positive protocol control, never as the user target.
        val prior = JSONObject(File(targetContext.filesDir, "remote_wifi_preview.json").readText())
        val array = prior.getJSONArray("points")
        val seeds = (0 until array.length()).map { array.getJSONObject(it).getString("bssid") }.take(6)
        val wire = koin.get<AppleWifiWlocClient>()
        val known = wire.query(seeds, prior.getDouble("latitude"), prior.getDouble("longitude"),
            RemoteWifiRegion.valueOf(prior.getString("region")))
        check(known.isNotEmpty()) { "Known WLOC protocol control failed" }
        val anchor = known.first()
        val control = AppleWifiDiscovery(wloc = wire, localSeeds = { _, _ -> seeds },
            policy = AppleWifiSearchPolicy(maxTileRequests = 1), tileLookup = { _, _, _, _, _ -> emptyList() })
        val cache = File(targetContext.cacheDir, "wloc-source-control-${UUID.randomUUID()}.json").also(temporary::add)
        val repository = RemoteWifiRepository(AppleWifiTileClient(), cache, discovery = control)
        val verified = repository.query(anchor.latitude, anchor.longitude, RemoteWifiRegion.valueOf(prior.getString("region")))
        check(verified.source == RemoteWifiSource.APPLE_WLOC && verified.points.isNotEmpty())
        check(verified.points.all { it.distanceMeters <= 300 })
        check(repository.loadLatest() == verified) { "WLOC provenance/cache did not round trip" }
        val persisted = JSONObject(cache.readText())
        check(persisted.getInt("version") == 3 && persisted.getString("source") == "Apple WLOC")
        report.put("independent_positive_control", JSONObject().put("initial_recorded_positions", known.size)
            .put("adopted_positions", verified.points.size).put("source", verified.source.name)
            .put("search", control.lastReport?.toJson()).put("version_3_cache_round_trip", true)
            .put("is_user_target", false))

        if (arguments?.getString("lookup-wifi-only") != "true") {
        val cells = koin.get<OpenCellSearchClient>().search(target.lat, target.lng,
            koin.get<SettingsRepository>().getOpencellidApiToken(), forceRefresh = true)
        report.put("extended_cell_search", cells.report.toJson())
        val cellResults = JSONArray()
        for (seed in cells.records.mapNotNull { it.appleSeed() }.take(3)) {
            try {
                val snapshot = koin.get<AppleCellClient>().query(target.lat, target.lng, region, seed)
                check(snapshot.points.all { it.distanceMeters <= 10_000 && it.identity.mcc == seed.mcc && it.identity.mnc == seed.mnc })
                cellResults.put(JSONObject().put("source", "Apple WLOC").put("records", snapshot.points.size)
                    .put("nearest_meters", snapshot.points.minOf { it.distanceMeters })
                    .put("farthest_meters", snapshot.points.maxOf { it.distanceMeters })); break
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { cellResults.put(JSONObject().put("error", error.javaClass.simpleName)) }
        }
        report.put("cell_expansion", cellResults)
        } else report.put("cell_search_skipped", true)
        val proxyAfter = proxy.status()
        report.put("proxy_before", JSONObject().put("running", proxyBefore.optBoolean("running")).put("leases", proxyBefore.optInt("leases", -1)))
            .put("proxy_after", JSONObject().put("running", proxyAfter.optBoolean("running")).put("leases", proxyAfter.optInt("leases", -1)))
        check(proxyAfter.optInt("leases", -1) == proxyBefore.optInt("leases", -2))
        check(original == transport.getString("desired_config", null)) { "Simulation config changed" }
        report.put("desired_config_unchanged", true)
        output.putString("diagnosis", report.toString()); output.putString("result", "PASS")
    } catch (error: Exception) { output.putString("result", "FAIL: ${error.stackTraceToString()}") }
    finally {
        temporary.forEach { AtomicFile(it).delete() }
        activity?.let { runOnMainSync { it.finish() } }
    }
    output
}
