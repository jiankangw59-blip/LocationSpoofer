package com.vincenthzr.locationspoofer

import android.os.Bundle
import android.os.SystemClock
import com.vincenthzr.locationspoofer.data.repository.SettingsRepository
import com.vincenthzr.locationspoofer.utils.CoordinateUtils.LatLng
import com.vincenthzr.locationspoofer.utils.FeedMeProxyLeaseProvider
import com.vincenthzr.locationspoofer.utils.GeoMath
import com.vincenthzr.locationspoofer.utils.RemoteWifiQueryException
import com.vincenthzr.locationspoofer.utils.WigleTransport
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.koin.core.context.GlobalContext
import java.io.File
import kotlin.math.cos

/** Counts source records without importing or publishing any environment changes. */
internal fun SystemFilesInstrumentation.diagnoseWigleCurrentArea(paged: Boolean = false): Bundle = runBlocking {
    val output = Bundle()
    var stage = "initialization"
    val rows = JSONArray()
    var lease: com.vincenthzr.locationspoofer.utils.RequestProxyLease? = null
    var http: OkHttpClient? = null
    try {
        val transportPrefs = targetContext.getSharedPreferences("framework_config_state", 0)
        val original = checkNotNull(transportPrefs.getString("desired_config", null))
        val config = JSONObject(original)
        val target = LatLng(config.getDouble("wgs84_lat"), config.getDouble("wgs84_lng"))
        val koin = withTimeout(20_000) {
            while (GlobalContext.getOrNull() == null) delay(100)
            GlobalContext.get()
        }
        stage = "wait_settings"
        val settings = withTimeout(20_000) {
            while (koin.getOrNull<SettingsRepository>() == null) delay(100)
            koin.get<SettingsRepository>()
        }
        val token = settings.getWigleApiToken().trim()
        check(token.isNotEmpty())
        stage = "wait_proxy"
        val proxy = withTimeout(20_000) {
            while (koin.getOrNull<FeedMeProxyLeaseProvider>() == null) delay(100)
            koin.get<FeedMeProxyLeaseProvider>()
        }
        val before = proxy.status()
        stage = "acquire_proxy"
        lease = withContext(NonCancellable) { proxy.acquire() }
        checkNotNull(lease)
        http = OkHttpClient.Builder().proxy(lease.proxy).connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS).callTimeout(15, java.util.concurrent.TimeUnit.SECONDS).build()
        val transport = WigleTransport(http)
        val candidates = linkedMapOf<String, JSONObject>()
        val activeWifi = config.getJSONObject("wifi_json").getJSONArray("nearbyWifi")
        val activeBssids = (0 until activeWifi.length()).map { activeWifi.getJSONObject(it).getString("bssid").lowercase() }.toSet()
        suspend fun query(label: String, radius: Int?, cursor: String? = null): String? {
            stage = label
            val latSpan = radius?.div(111_320.0) ?: 0.002
            val lngSpan = radius?.div(111_320.0 * cos(Math.toRadians(target.lat))) ?: 0.002
            val builder = "https://api.wigle.net/api/v2/network/search".toHttpUrl().newBuilder()
                .addQueryParameter("latrange1", (target.lat - latSpan).toString())
                .addQueryParameter("latrange2", (target.lat + latSpan).toString())
                .addQueryParameter("longrange1", (target.lng - lngSpan).toString())
                .addQueryParameter("longrange2", (target.lng + lngSpan).toString())
                .addQueryParameter("resultsPerPage", "100")
            if (radius != null && !paged) builder.addQueryParameter("closestLat", target.lat.toString())
                .addQueryParameter("closestLong", target.lng.toString())
            if (cursor != null) builder.addQueryParameter("searchAfter", cursor)
            val request = Request.Builder().url(builder.build()).header("Authorization", "Basic $token")
                .header("Accept", "application/json").build()
            val start = SystemClock.elapsedRealtime()
            val entry = JSONObject().put("query", label).put("lat_span", latSpan).put("lng_span", lngSpan)
            try {
                val body = JSONObject(transport.fetch(request).toString(Charsets.UTF_8))
                entry.put("http", 200).put("success", body.optBoolean("success"))
                val data = body.optJSONArray("results") ?: JSONArray()
                val seen = mutableSetOf<String>()
                val distances = mutableListOf<Double>()
                var withinRadius = 0; var within2km = 0; var invalid = 0; var named = 0; var frequencies = 0
                for (i in 0 until data.length()) {
                    val point = data.optJSONObject(i) ?: continue
                    val bssid = point.optString("netid").trim().lowercase()
                    val lat = point.optDouble("trilat", Double.NaN); val lng = point.optDouble("trilong", Double.NaN)
                    if (!bssid.matches(Regex("[0-9a-f]{2}(:[0-9a-f]{2}){5}")) ||
                        lat !in -90.0..90.0 || lng !in -180.0..180.0) { invalid++; continue }
                    if (!seen.add(bssid)) continue
                    val distance = GeoMath.distance(target, LatLng(lat, lng))
                    distances += distance
                    if (distance <= (radius ?: 2000)) withinRadius++
                    if (distance <= 2000) {
                        within2km++
                        candidates[bssid] = JSONObject().put("bssid", bssid).put("latitude", lat)
                            .put("longitude", lng).put("distance_meters", distance)
                    }
                    if (point.optString("ssid").isNotEmpty()) named++
                    if (point.optInt("frequency", 0) > 0) frequencies++
                }
                entry.put("raw_records", data.length()).put("total_results", body.optLong("totalResults", -1))
                    .put("unique_valid_bssids", seen.size).put("invalid_records", invalid)
                    .put("within_radius", withinRadius).put("within_2km", within2km)
                    .put("with_ssid", named).put("with_frequency", frequencies)
                    .put("response_fields", JSONArray(body.keys().asSequence().toList()))
                if (distances.isNotEmpty()) entry.put("nearest_meters", distances.min()).put("farthest_meters", distances.max())
                val next = body.optString("searchAfter").takeIf { it.isNotBlank() && it != "null" }
                entry.put("has_cursor", next != null)
                sendStatus(1, Bundle().apply { putString("progress", "$label: ${data.length()} records") })
                return next.takeIf { data.length() > 0 }
            } catch (error: Exception) {
                entry.put("error", error.javaClass.simpleName)
                (error as? RemoteWifiQueryException)?.let { entry.put("http", it.httpStatus) }
                throw error
            } finally { entry.put("milliseconds", SystemClock.elapsedRealtime() - start); rows.put(entry) }
        }
        if (paged) {
            for (radius in listOf<Int?>(null, 2000)) {
                val seenCursors = mutableSetOf<String>()
                var next: String? = null
                for (page in 1..3) {
                    next = query("${if (radius == null) "original_box" else "radius_2000m_no_sort"}_page_$page", radius, next)
                    if (next == null || !seenCursors.add(next)) break
                }
            }
        } else {
            query("original_degree_box", null)
            query("radius_500m", 500)
            query("radius_1000m", 1000)
            val next = query("radius_2000m_page_1", 2000)
            if (next != null) query("radius_2000m_page_2", 2000, next)
        }
        val extra = candidates.filterKeys { it !in activeBssids }.values.sortedBy { it.getDouble("distance_meters") }
        File(targetContext.cacheDir, "wigle-current-seed-candidates.json").writeText(JSONObject()
            .put("latitude", target.lat).put("longitude", target.lng).put("points", JSONArray(extra)).toString())
        withContext(NonCancellable) { lease.release() }; lease = null
        val after = proxy.status()
        output.putString("diagnosis", JSONObject().put("latitude", target.lat).put("longitude", target.lng)
            .put("queries", rows).put("unique_within_2km", candidates.size).put("additional_seed_candidates", extra.size)
            .put("desired_config_unchanged", original == transportPrefs.getString("desired_config", null))
            .put("proxy_before", JSONObject().put("running", before.optBoolean("running")).put("leases", before.optInt("leases", -1)))
            .put("proxy_after", JSONObject().put("running", after.optBoolean("running")).put("leases", after.optInt("leases", -1)))
            .toString())
        output.putString("result", "PASS")
    } catch (error: Exception) {
        output.putString("diagnosis", JSONObject().put("queries", rows).toString())
        output.putString("result", "FAIL: $stage/${error.javaClass.simpleName}")
    } finally {
        withContext(NonCancellable) { lease?.release() }
        http?.connectionPool?.evictAll(); http?.dispatcher?.executorService?.shutdown()
    }
    output
}
