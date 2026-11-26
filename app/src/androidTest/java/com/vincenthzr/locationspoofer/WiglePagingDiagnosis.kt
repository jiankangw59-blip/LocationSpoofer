package com.vincenthzr.locationspoofer

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.vincenthzr.locationspoofer.data.model.RemoteWifiRegion
import com.vincenthzr.locationspoofer.data.repository.SettingsRepository
import com.vincenthzr.locationspoofer.utils.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.koin.core.context.GlobalContext
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.math.cos

/** Single-variable wire controls. No imports, cache writes, mock-state changes or application interactions. */
internal fun SystemFilesInstrumentation.diagnoseWiglePaging(arguments: Bundle?): Bundle = runBlocking {
    val output = Bundle(); val cases = JSONArray(); val expansions = JSONArray()
    var activity: Activity? = null; var lease: RequestProxyLease? = null; var http: OkHttpClient? = null
    val report = JSONObject()
    fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }.take(16)
    try {
        activity = startActivitySync(Intent(targetContext, LookupDiagnosticsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val prefs = targetContext.getSharedPreferences("framework_config_state", 0)
        val original = checkNotNull(prefs.getString("desired_config", null)); val config = JSONObject(original)
        val stored = targetContext.getSharedPreferences("app_settings", 0)
        val inputLat = arguments?.getString("lookup-lat")?.toDoubleOrNull()
        val inputLon = arguments?.getString("lookup-lon")?.toDoubleOrNull()
        require((inputLat == null) == (inputLon == null))
        val point = if (inputLat != null && inputLon != null) CoordinateUtils.gcj02ToWgs84(inputLat, inputLon)
            else if (config.optBoolean("active")) CoordinateUtils.LatLng(config.getDouble("wgs84_lat"), config.getDouble("wgs84_lng"))
            else CoordinateUtils.gcj02ToWgs84(checkNotNull(stored.all["last_spoofed_lat"]).toString().toDouble(),
                checkNotNull(stored.all["last_spoofed_lng"]).toString().toDouble())
        require(point.lat in -85.0..85.0 && point.lng in -180.0..180.0 && (point.lat != 0.0 || point.lng != 0.0))
        val koin = withTimeout(20_000) {
            while (GlobalContext.getOrNull()?.getOrNull<FeedMeProxyLeaseProvider>() == null) delay(100)
            GlobalContext.get()
        }
        val provider = koin.get<FeedMeProxyLeaseProvider>(); val before = provider.status()
        val token = koin.get<SettingsRepository>().getWigleApiToken().trim(); check(token.isNotBlank())
        lease = withContext(NonCancellable) { provider.acquire() }; checkNotNull(lease)
        http = OkHttpClient.Builder().proxy(lease.proxy).connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build()
        val transport = WigleTransport(http)
        val now = System.currentTimeMillis()
        val date = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)
        fun daysAgo(days: Long) = date.format(Instant.ofEpochMilli(now - days * 86_400_000L))
        val radius = 3000.0
        val spanLat = Math.toDegrees(radius / GeoMath.EARTH_RADIUS_M)
        val spanLon = spanLat / cos(Math.toRadians(point.lat))
        val candidates = linkedSetOf<String>()
        val profiles = listOf("baseline" to emptyMap(), "updated_3y" to mapOf("lastupdt" to daysAgo(1096)),
            "observed_548d" to mapOf("lasttime" to daysAgo(548)), "quality_1" to mapOf("minQoS" to "1"),
            "future_update_negative_control" to mapOf("lastupdt" to "21000101"))
        withTimeout(120_000) {
            for ((label, filters) in profiles) {
                val pages = JSONArray(); val seen = mutableSetOf<String>(); val cursors = mutableSetOf<String>()
                var cursor: String? = null
                var stop = "PAGE_LIMIT"
                for (page in 1..if (label == "future_update_negative_control") 1 else 2) {
                    val builder = "https://api.wigle.net/api/v2/network/search".toHttpUrl().newBuilder()
                        .addQueryParameter("latrange1", (point.lat - spanLat).toString())
                        .addQueryParameter("latrange2", (point.lat + spanLat).toString())
                        .addQueryParameter("longrange1", (point.lng - spanLon).toString())
                        .addQueryParameter("longrange2", (point.lng + spanLon).toString()).addQueryParameter("resultsPerPage", "100")
                    filters.forEach { (key, value) -> builder.addQueryParameter(key, value) }
                    cursor?.let { builder.addQueryParameter("searchAfter", it) }
                    val request = Request.Builder().url(builder.build()).header("Authorization", "Basic $token")
                        .header("Accept", "application/json").build()
                    val body = JSONObject(transport.fetch(request).toString(Charsets.UTF_8)); check(body.optBoolean("success"))
                    val records = body.getJSONArray("results"); val sample = JSONArray(); var newIds = 0; var duplicateIds = 0
                    for (i in 0 until records.length()) {
                        val row = records.getJSONObject(i); val id = row.optString("netid").trim().lowercase()
                        val valid = id.matches(Regex("[0-9a-f]{2}(:[0-9a-f]{2}){5}"))
                        if (valid && seen.add(id)) newIds++ else if (valid) duplicateIds++
                        val lat = row.optDouble("trilat", Double.NaN); val lon = row.optDouble("trilong", Double.NaN)
                        val distance = if (lat in -90.0..90.0 && lon in -180.0..180.0)
                            GeoMath.distance(point, CoordinateUtils.LatLng(lat, lon)) else null
                        val seenAt = runCatching { Instant.parse(row.optString("lasttime")).toEpochMilli() }.getOrNull()
                        if (valid && distance != null && distance <= radius &&
                            (seenAt == null || now - seenAt in -300_000..548L * 86_400_000)) candidates += id
                        sample.put(JSONObject().put("bssid_hash", hash(id)).putOpt("distance_meters", distance)
                            .putOpt("lasttime", row.opt("lasttime")).putOpt("lastupdt", row.opt("lastupdt")).putOpt("qos", row.opt("qos")))
                    }
                    val rawCursor = body.opt("searchAfter")
                    val next = (rawCursor as? String)?.takeIf { it.isNotBlank() }
                    pages.put(JSONObject().put("page", page).put("query_keys", JSONArray(request.url.queryParameterNames.sorted()))
                        .putOpt("request_cursor_hash", cursor?.let(::hash)).putOpt("response_cursor_hash", next?.let(::hash))
                        .put("cursor_type", if (rawCursor == null || rawCursor == JSONObject.NULL) "NULL" else rawCursor.javaClass.simpleName)
                        .put("deprecated_integer_cursor_present", body.has("search_after")).put("raw_records", records.length())
                        .put("new_bssids", newIds).put("duplicate_bssids", duplicateIds).put("records", sample))
                    sendStatus(1, Bundle().apply { putString("progress", "$label page $page: ${records.length()} records, $newIds new") })
                    if (records.length() == 0) { stop = "EMPTY_PAGE"; break }
                    if (newIds == 0) { stop = "NO_NEW_BSSID"; break }
                    if (next == null) { stop = "NO_STRING_CURSOR"; break }
                    if (next == cursor || !cursors.add(next)) { stop = "REPEATED_CURSOR"; break }
                    cursor = next
                }
                cases.put(JSONObject().put("profile", label).put("filters", JSONObject(filters)).put("pages", pages).put("stop", stop))
            }
            val region = if (point.lat in 18.0..54.0 && point.lng in 73.0..135.0) RemoteWifiRegion.MAINLAND_CHINA else RemoteWifiRegion.GLOBAL
            for (batch in candidates.take(18).chunked(6)) {
                val entry = JSONObject().put("seed_count", batch.size)
                try {
                    val positions = koin.get<AppleWifiWlocClient>().query(batch, point.lat, point.lng, region)
                    entry.put("valid_positions", positions.size).put("within_300m", positions.count { it.distanceMeters <= 300 })
                        .putOpt("nearest_meters", positions.minOfOrNull { it.distanceMeters })
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) { entry.put("error", error.javaClass.simpleName) }
                expansions.put(entry)
            }
        }
        withContext(NonCancellable) { lease.release() }; lease = null
        val after = provider.status()
        check(original == prefs.getString("desired_config", null)); check(before.optInt("leases") == after.optInt("leases"))
        report.put("latitude", point.lat).put("longitude", point.lng).put("radius_meters", radius)
            .put("cases", cases).put("wloc_checks", expansions).put("unique_usable_seed_candidates", candidates.size)
            .put("desired_config_unchanged", true)
            .put("proxy_before", JSONObject().put("running", before.optBoolean("running")).put("leases", before.optInt("leases")))
            .put("proxy_after", JSONObject().put("running", after.optBoolean("running")).put("leases", after.optInt("leases")))
        output.putString("result", "PASS")
    } catch (error: Exception) { output.putString("result", "FAIL: ${error.stackTraceToString()}") }
    finally {
        report.put("cases", cases).put("wloc_checks", expansions); output.putString("diagnosis", report.toString())
        withContext(NonCancellable) { lease?.release() }
        http?.connectionPool?.evictAll(); http?.dispatcher?.executorService?.shutdown()
        activity?.let { runOnMainSync { it.finish() } }
    }
    output
}
