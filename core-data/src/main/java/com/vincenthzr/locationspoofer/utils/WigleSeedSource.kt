package com.vincenthzr.locationspoofer.utils

import com.vincenthzr.locationspoofer.data.model.RemoteWifiMetadata
import com.vincenthzr.locationspoofer.utils.CoordinateUtils.LatLng
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos

data class WifiSeed(val bssid: String, val distanceMeters: Double?, val observedAt: Long? = null,
    val metadata: RemoteWifiMetadata? = null)

data class WigleSeedFilters(val maxUpdatedAgeDays: Long? = 1096, val minQoS: Int? = 1) {
    init { require(maxUpdatedAgeDays == null || maxUpdatedAgeDays in 1..3650); require(minQoS == null || minQoS in 0..7) }
}

data class WigleSeedResult(val seeds: List<WifiSeed>, val requests: Int, val rawRecords: Int,
    val staleRecords: Int, val invalidRecords: Int, val repeatedPage: Boolean, val truncated: Boolean,
    val filterViolations: Int = 0, val pages: List<JSONObject> = emptyList(), val limits: Set<String> = emptySet())

/** Area lookup supplies hints only. An upstream filter is checked locally before accepting a seed. */
class WigleSeedSource(private val transport: WigleTransport = WigleTransport(),
    private val filters: WigleSeedFilters = WigleSeedFilters(), private val clock: () -> Long = System::currentTimeMillis) {
    internal suspend fun <T> withSession(block: suspend (WigleSeedSource) -> T): T =
        transport.withSession { scoped -> block(WigleSeedSource(scoped, filters, clock)) }

    suspend fun query(latitude: Double, longitude: Double, radiusMeters: Double, token: String,
        maxRequests: Int = 2): WigleSeedResult =
        query(latitude, longitude, radiusMeters, token, maxRequests) {}

    internal suspend fun query(latitude: Double, longitude: Double, radiusMeters: Double, token: String,
        maxRequests: Int, onInterrupted: (WigleSeedResult) -> Unit): WigleSeedResult {
        require(latitude.isFinite() && longitude.isFinite() && latitude in -85.05112878..85.05112878 && longitude in -180.0..180.0)
        require(radiusMeters in 100.0..8_000.0 && maxRequests in 1..2)
        if (token.isBlank()) return WigleSeedResult(emptyList(), 0, 0, 0, 0, false, false)
        val latSpan = Math.toDegrees(radiusMeters / GeoMath.EARTH_RADIUS_M)
        val lonSpan = latSpan / cos(Math.toRadians(latitude))
        val normalizedLon = if (longitude == 180.0) -180.0 else longitude
        val low = normalizedLon - lonSpan; val high = normalizedLon + lonSpan
        val spans = when {
            low < -180 -> listOf(low + 360 to 180.0, -180.0 to high)
            high > 180 -> listOf(low to 180.0, -180.0 to high - 360)
            else -> listOf(low to high)
        }
        val now = clock()
        val updatedSince = filters.maxUpdatedAgeDays?.let { days -> Instant.ofEpochMilli(now - days * 86_400_000L)
            .atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant() }
        val updatedParameter = updatedSince?.let { DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT).withZone(ZoneOffset.UTC).format(it) }
        val seeds = linkedMapOf<String, WifiSeed>(); val pages = mutableListOf<JSONObject>(); val limits = linkedSetOf<String>()
        var requests = 0; var raw = 0; var stale = 0; var invalid = 0; var violations = 0
        var repeated = false; var truncated = false
        fun result(candidates: List<WifiSeed>): WigleSeedResult {
            if (violations > 0) limits += "FILTER_CONTRACT_MISMATCH"
            return WigleSeedResult(candidates, requests, raw, stale, invalid, repeated, truncated,
                violations, pages.toList(), limits.toSet())
        }
        try {
            for ((spanIndex, span) in spans.withIndex()) {
                val (west, east) = span
                var cursor: String? = null
                val cursors = mutableSetOf<String>(); val seenBssids = mutableSetOf<String>()
                var page = 0
                while (true) {
                    if (requests >= maxRequests) { truncated = true; limits += "REQUEST_LIMIT"; break }
                    val url = "https://api.wigle.net/api/v2/network/search".toHttpUrl().newBuilder()
                        .addQueryParameter("latrange1", (latitude - latSpan).coerceAtLeast(-90.0).toString())
                        .addQueryParameter("latrange2", (latitude + latSpan).coerceAtMost(90.0).toString())
                        .addQueryParameter("longrange1", west.toString()).addQueryParameter("longrange2", east.toString())
                        .addQueryParameter("resultsPerPage", "100")
                    updatedParameter?.let { url.addQueryParameter("lastupdt", it) }
                    filters.minQoS?.let { url.addQueryParameter("minQoS", it.toString()) }
                    cursor?.let { url.addQueryParameter("searchAfter", it) }
                    val request = Request.Builder().url(url.build()).header("Authorization", "Basic ${token.trim()}")
                        .header("Accept", "application/json").build()
                    requests++; page++
                    val json = try { JSONObject(transport.fetch(request).toString(Charsets.UTF_8)) }
                        catch (error: CancellationException) { throw error }
                        catch (error: IllegalArgumentException) { throw IOException("Invalid WiGLE seed response") }
                    if (!json.optBoolean("success")) throw IOException("WiGLE seed query rejected")
                    val rows = json.optJSONArray("results") ?: throw IOException("Missing WiGLE seed records")
                    if (rows.length() > 100) throw IOException("WiGLE seed page exceeded limit")
                    raw += rows.length()
                    var newIds = 0; var duplicates = 0; val violationsBefore = violations
                    for (index in 0 until rows.length()) {
                        val row = rows.optJSONObject(index)
                        if (row == null) { invalid++; continue }
                        val bssid = WifiBssid.normalize(row.optString("netid"))
                        if (bssid == null) { invalid++; continue }
                        if (seenBssids.add(bssid)) newIds++ else duplicates++
                        val lat = row.optDouble("trilat", Double.NaN); val lon = row.optDouble("trilong", Double.NaN)
                        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) { invalid++; continue }
                        val distance = GeoMath.distance(LatLng(latitude, longitude), LatLng(lat, lon))
                        if (distance > radiusMeters + 1.0) { invalid++; continue }
                        val observed = timestamp(row.opt("lasttime")); val updated = timestamp(row.opt("lastupdt"))
                        if (listOf("lasttime", "lastupdt").any { key ->
                                val value = row.opt(key)
                                value != null && value != JSONObject.NULL && value.toString().isNotBlank() && timestamp(value) == null
                            }) { invalid++; continue }
                        if (observed != null && observed > now + 300_000 || updated != null && updated > now + 300_000) { invalid++; continue }
                        val qos = row.opt("qos")?.takeUnless { it == JSONObject.NULL }?.toString()?.toIntOrNull()
                        if (qos != null && qos !in 0..7) { invalid++; continue }
                        if (updatedSince != null && updated != null && updated < updatedSince.toEpochMilli() ||
                            filters.minQoS != null && qos != null && qos < filters.minQoS) {
                            violations++; invalid++; continue
                        }
                        if (observed != null && now - observed > 548L * 86_400_000) { stale++; continue }
                        val ssid = row.optString("ssid").takeIf { row.has("ssid") && !row.isNull("ssid") && it.toByteArray(Charsets.UTF_8).size <= 32 }
                        val frequency = row.optInt("frequency", 0).takeIf { WifiMetadataCodec.frequencyToChannel(it) != null }
                        val metadata = RemoteWifiMetadata(ssid, frequency, frequency?.let(WifiMetadataCodec::frequencyToChannel),
                            row.optString("encryption").takeIf { it.isNotEmpty() && it.length <= 64 }, now, observed)
                        val seed = WifiSeed(bssid, distance, observed, metadata)
                        val old = seeds[bssid]
                        if (old == null || order.compare(seed, old) < 0) seeds[bssid] = seed
                    }
                    val rawCursor = json.opt("searchAfter")
                    val next = (rawCursor as? String)?.takeIf { it.isNotBlank() }
                    val trace = JSONObject().put("span", spanIndex).put("page", page).put("raw_records", rows.length())
                        .put("new_bssids", newIds).put("duplicate_bssids", duplicates).put("filter_violations", violations - violationsBefore)
                        .putOpt("requested_lastupdt", updatedParameter).putOpt("requested_minQoS", filters.minQoS)
                        .putOpt("request_cursor_hash", cursor?.let(::cursorHash)).putOpt("response_cursor_hash", next?.let(::cursorHash))
                        .put("cursor_type", if (rawCursor == null || rawCursor == JSONObject.NULL) "NULL" else rawCursor.javaClass.simpleName)
                    pages += trace
                    if (rows.length() == 0) { trace.put("stop", "EMPTY_PAGE"); break }
                    if (newIds == 0) { repeated = true; limits += "NO_NEW_BSSID"; trace.put("stop", "NO_NEW_BSSID"); break }
                    if (rawCursor != null && rawCursor != JSONObject.NULL && rawCursor !is String || next != null && next.length > 4096) {
                        truncated = true; limits += "INVALID_CURSOR"; trace.put("stop", "INVALID_CURSOR"); break
                    }
                    if (next == null) { trace.put("stop", "NO_CURSOR"); break }
                    if (next == cursor || !cursors.add(next)) {
                        repeated = true; limits += "REPEATED_CURSOR"; trace.put("stop", "REPEATED_CURSOR"); break
                    }
                    cursor = next
                }
            }
        } catch (error: Exception) {
            try {
                // The interrupted lookup is reported without making its seeds available to callers.
                onInterrupted(result(emptyList()))
            } catch (diagnosticError: Exception) {
                if (diagnosticError !== error) error.addSuppressed(diagnosticError)
            }
            throw error
        }
        if (seeds.size > 64) { truncated = true; limits += "SEED_LIMIT" }
        return result(seeds.values.sortedWith(order).take(64))
    }

    companion object {
        internal val order = compareBy<WifiSeed> { it.observedAt == null }.thenBy { it.distanceMeters ?: Double.MAX_VALUE }
            .thenByDescending { it.observedAt ?: 0 }
        private fun cursorHash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }.take(16)
        private fun timestamp(raw: Any?): Long? {
            if (raw == null || raw == JSONObject.NULL) return null
            val text = raw.toString()
            text.toLongOrNull()?.takeIf { it > 0 }?.let {
                if (it > Long.MAX_VALUE / 1000) return null
                return if (it >= 1_000_000_000_000) it else it * 1000
            }
            return runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
        }
    }
}
