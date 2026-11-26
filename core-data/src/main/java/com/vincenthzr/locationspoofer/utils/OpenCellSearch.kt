package com.vincenthzr.locationspoofer.utils

import com.vincenthzr.locationspoofer.data.model.RemoteCellIdentity
import com.vincenthzr.locationspoofer.data.model.RemoteCellRadio
import com.vincenthzr.locationspoofer.utils.CoordinateUtils.LatLng
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.*

data class OpenCellSearchPolicy(
    val rings: Int = 2,
    val tileSideMeters: Double = 1_800.0,
    val maxRequests: Int = 32,
    val maxPagesPerBox: Int = 3,
    val pageSize: Int = 50,
    val timeBudgetMillis: Long = 35_000,
    val maxAgeMillis: Long = 548L * 86_400_000,
    val maxDistanceMeters: Double = 10_000.0,
    val maxRecords: Int = 256,
    val outerProbeMeters: Double = 8_000.0
) {
    init {
        require(rings in 0..2 && tileSideMeters in 100.0..1_800.0)
        require(maxRequests in 1..32 && maxPagesPerBox in 1..3 && pageSize in 1..50)
        require(timeBudgetMillis in 1..35_000 && maxAgeMillis > 0 && maxDistanceMeters > 0 && maxRecords in 1..256)
        require(outerProbeMeters in 0.0..8_000.0)
    }
}

data class OpenCellBox(val latMin: Double, val lonMin: Double, val latMax: Double, val lonMax: Double) {
    val parameter get() = "$latMin,$lonMin,$latMax,$lonMax"
    val areaMetersSquared get() = GeoMath.EARTH_RADIUS_M.pow(2) * Math.toRadians(lonMax - lonMin) *
        (sin(Math.toRadians(latMax)) - sin(Math.toRadians(latMin)))
    fun contains(lat: Double, lon: Double): Boolean {
        // The area endpoint rounds positions to three decimals. Permit that quantization only.
        val tolerance = 0.00051
        return lat in (latMin - tolerance)..(latMax + tolerance) && lon in (lonMin - tolerance)..(lonMax + tolerance)
    }
}

internal object OpenCellGrid {
    fun boxes(lat: Double, lon: Double, policy: OpenCellSearchPolicy): List<OpenCellBox> {
        require(lat.isFinite() && lon.isFinite() && lat in -85.05112878..85.05112878 && lon in -180.0..180.0)
        val stepLat = Math.toDegrees(policy.tileSideMeters / GeoMath.EARTH_RADIUS_M)
        val stepLon = stepLat / cos(Math.toRadians(lat))
        val offsets = (-policy.rings..policy.rings).flatMap { y ->
            (-policy.rings..policy.rings).map { x -> x to y }
        }.sortedWith(compareBy<Pair<Int, Int>> { max(abs(it.first), abs(it.second)) }
            .thenBy { it.first * it.first + it.second * it.second }.thenBy { it.second }.thenBy { it.first })
        val probes = if (policy.rings == 2 && policy.outerProbeMeters > 0) {
            val step = policy.outerProbeMeters / policy.tileSideMeters
            listOf(step to 0.0, -step to 0.0, 0.0 to step, 0.0 to -step)
        } else emptyList()
        return (offsets.map { it.first.toDouble() to it.second.toDouble() } + probes).flatMap { (x, y) ->
            val lowLat = (lat + (y - 0.5) * stepLat).coerceAtLeast(-90.0)
            val highLat = (lat + (y + 0.5) * stepLat).coerceAtMost(90.0)
            val centerLon = ((lon + x * stepLon + 540.0) % 360.0) - 180.0
            val lowLon = centerLon - stepLon / 2; val highLon = centerLon + stepLon / 2
            when {
                lowLon < -180 -> listOf(OpenCellBox(lowLat, lowLon + 360, highLat, 180.0), OpenCellBox(lowLat, -180.0, highLat, highLon))
                highLon > 180 -> listOf(OpenCellBox(lowLat, lowLon, highLat, 180.0), OpenCellBox(lowLat, -180.0, highLat, highLon - 360))
                else -> listOf(OpenCellBox(lowLat, lowLon, highLat, highLon))
            }
        }.distinct().onEach { require(it.areaMetersSquared in 0.0..4_000_000.0) }
    }
}

enum class OpenCellFreshness { RECENT, UNKNOWN }

data class OpenCellRecord(
    val radio: String, val mcc: Int, val mnc: Int, val area: Int, val cellId: Long,
    val latitude: Double, val longitude: Double, val distanceMeters: Double,
    val updatedAtMillis: Long?, val samples: Int?, val freshness: OpenCellFreshness,
    val sourceJson: String
) {
    val identityKey get() = "$radio:$mcc:$mnc:$area:$cellId"
    fun appleSeed(): RemoteCellIdentity? {
        val type = when (radio) { "LTE" -> RemoteCellRadio.LTE; "NR" -> RemoteCellRadio.NR; else -> return null }
        return RemoteCellIdentity(type, mcc, mnc, area, cellId).also { it.validate() }
    }
}

data class OpenCellPage(val box: Int, val offset: Int, val returned: Int, val reportedCount: Long?, val newRecords: Int)

data class OpenCellSearchReport(
    val latitude: Double, val longitude: Double, val fetchedAt: Long,
    val plannedBoxes: Int, val requestedBoxes: Int, val completedBoxes: Int,
    val requests: Int, val rawRecords: Int, val uniqueRecords: Int, val duplicates: Int,
    val recentRecords: Int, val unknownAgeRecords: Int,
    val rejected: Map<String, Int>, val radios: Map<String, Int>,
    val limits: Set<String>, val pages: List<OpenCellPage>, val elapsedMillis: Long,
    val fromCache: Boolean = false
) {
    val complete get() = completedBoxes == plannedBoxes && limits.isEmpty()
    fun toJson(): JSONObject = JSONObject().put("latitude", latitude).put("longitude", longitude)
        .put("fetched_at", fetchedAt).put("planned_boxes", plannedBoxes).put("requested_boxes", requestedBoxes)
        .put("completed_boxes", completedBoxes).put("requests", requests).put("raw_records", rawRecords)
        .put("unique_records", uniqueRecords).put("duplicates", duplicates).put("recent_records", recentRecords)
        .put("unknown_age_records", unknownAgeRecords).put("rejected", JSONObject(rejected)).put("radios", JSONObject(radios))
        .put("limits", JSONArray(limits.sorted())).put("complete_within_planned_boxes", complete)
        .put("elapsed_millis", elapsedMillis).put("from_cache", fromCache)
        .put("pages", JSONArray(pages.map { JSONObject().put("box", it.box).put("offset", it.offset)
            .put("returned", it.returned).putOpt("reported_count", it.reportedCount).put("new_records", it.newRecords) }))
}

data class OpenCellSearchResult(val records: List<OpenCellRecord>, val report: OpenCellSearchReport)
class OpenCellLookupException(val httpStatus: Int? = null, val apiCode: Int? = null) : IOException("OpenCellID request failed")
class OpenCellSearchIncompleteException : IOException("OpenCellID search ended before the planned area was exhausted")

/** A finite on-demand search. No timers, invented identities, credentials in reports, or background refresh. */
class OpenCellSearchClient(
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build(),
    private val policy: OpenCellSearchPolicy = OpenCellSearchPolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private val mutex = Mutex()
    @Volatile var lastReport: OpenCellSearchReport? = null
        private set
    private var cachedKey: String? = null
    private var cachedAt = 0L
    private var cached: OpenCellSearchResult? = null

    suspend fun validateToken(token: String): Boolean {
        if (token.isBlank()) return false
        return try {
            val url = "https://opencellid.org/cell/getInAreaSize".toHttpUrl().newBuilder()
                .addQueryParameter("key", token.trim()).addQueryParameter("BBOX", "0.0,0.0,0.001,0.001")
                .addQueryParameter("format", "json").build()
            val json = JSONObject(fetch(Request.Builder().url(url).build()).toString(Charsets.UTF_8))
            !json.has("error") && json.optLong("count", -1) >= 0
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { false }
    }

    suspend fun search(lat: Double, lon: Double, token: String, radio: RemoteCellRadio? = null,
        forceRefresh: Boolean = false): OpenCellSearchResult = mutex.withLock {
        val boxes = OpenCellGrid.boxes(lat, lon, policy)
        val started = monotonicMillis(); val now = clock()
        val key = MessageDigest.getInstance("SHA-256").digest("$lat,$lon,$radio,${token.trim()}".toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        if (!forceRefresh && key == cachedKey && started - cachedAt in 0..120_000 && cached != null) {
            return@withLock cached!!.copy(report = cached!!.report.copy(fromCache = true)).also { lastReport = it.report }
        }
        val records = linkedMapOf<String, OpenCellRecord>()
        val rejected = linkedMapOf<String, Int>(); val radios = linkedMapOf<String, Int>()
        val limits = linkedSetOf<String>(); val pages = mutableListOf<OpenCellPage>()
        var requests = 0; var raw = 0; var duplicates = 0; var completed = 0
        val visited = mutableSetOf<Int>()
        fun reject(reason: String) { rejected[reason] = (rejected[reason] ?: 0) + 1 }
        fun report(): OpenCellSearchReport {
            val retained = records.values.sortedWith(recordOrder).take(policy.maxRecords)
            return OpenCellSearchReport(lat, lon, now, boxes.size, visited.size, completed, requests, raw,
                retained.size, duplicates, retained.count { it.freshness == OpenCellFreshness.RECENT },
                retained.count { it.freshness == OpenCellFreshness.UNKNOWN }, rejected.toMap(), radios.toMap(), limits.toSet(),
                pages.toList(), (monotonicMillis() - started).coerceAtLeast(0))
        }
        try {
            if (token.isBlank()) limits += "NOT_CONFIGURED"
            else {
                val finished = withTimeoutOrNull(policy.timeBudgetMillis) {
                    boxLoop@ for ((boxIndex, box) in boxes.withIndex()) {
                        var offset = 0
                        var reportedCount: Long? = null
                        val pageSignatures = mutableSetOf<String>()
                        for (pageIndex in 0 until policy.maxPagesPerBox) {
                            if (requests >= policy.maxRequests) { limits += "REQUEST_LIMIT"; break@boxLoop }
                            ensureActive()
                            visited += boxIndex; requests++
                            val builder = "https://opencellid.org/cell/getInArea".toHttpUrl().newBuilder()
                                .addQueryParameter("key", token.trim()).addQueryParameter("BBOX", box.parameter)
                                .addQueryParameter("format", "json").addQueryParameter("limit", policy.pageSize.toString())
                                .addQueryParameter("offset", offset.toString())
                            radio?.let { builder.addQueryParameter("radio", it.name) }
                            val json = JSONObject(fetch(Request.Builder().url(builder.build()).build()).toString(Charsets.UTF_8))
                            if (json.has("error") || json.optString("stat") == "fail")
                                throw OpenCellLookupException(apiCode = json.optInt("code", -1).takeIf { it >= 0 })
                            val cells = json.optJSONArray("cells") ?: throw OpenCellLookupException()
                            require(cells.length() <= 50) { "OpenCellID page exceeded the documented limit" }
                            val count = json.optLong("count", -1).takeIf { it >= 0 }
                            // A count can shrink between pages. Keep the previous value only when this page omits it.
                            if (count != null) reportedCount = count
                            val before = records.size; raw += cells.length()
                            val identities = mutableListOf<String>()
                            for (index in 0 until cells.length()) {
                                val source = cells.optJSONObject(index)
                                if (source == null) { reject("INVALID_RECORD"); continue }
                                val technology = normalizeRadio(source.optString("radio"))
                                identities += listOf(technology, source.opt("mcc"), source.opt("mnc"),
                                    source.opt("tac"), source.opt("lac"), source.opt("cellid"), source.opt("cell"),
                                    source.opt("ci"), source.opt("cid"), source.opt("nci")).joinToString(":")
                                radios[technology.ifEmpty { "UNKNOWN" }] = (radios[technology.ifEmpty { "UNKNOWN" }] ?: 0) + 1
                                val parsed = parse(source, technology, lat, lon, now, box, radio, ::reject) ?: continue
                                val prior = records[parsed.identityKey]
                                if (prior != null) duplicates++
                                if (prior == null || recordOrder.compare(parsed, prior) < 0) records[parsed.identityKey] = parsed
                            }
                            pages += OpenCellPage(boxIndex, offset, cells.length(), count, records.size - before)
                            if (cells.length() == 0) {
                                if (reportedCount?.let { it > offset } == true) limits += "EMPTY_PAGE_WITH_REMAINING_COUNT"
                                else completed++
                                break
                            }
                            val signature = identities.sorted().joinToString("|").let { bytes ->
                                MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
                            }
                            if (!pageSignatures.add(signature)) { limits += "REPEATED_PAGE"; break }
                            val more = reportedCount?.let { it > offset + cells.length() } == true || cells.length() >= policy.pageSize
                            if (!more) { completed++; break }
                            if (pageIndex + 1 == policy.maxPagesPerBox) { limits += "PAGE_LIMIT"; break }
                            offset += cells.length()
                        }
                    }
                    true
                }
                if (finished != true) limits += "TIME_LIMIT"
            }
            if (records.size > policy.maxRecords) limits += "RECORD_LIMIT"
            val result = OpenCellSearchResult(records.values.sortedWith(recordOrder).take(policy.maxRecords), report())
            lastReport = result.report
            // Only a successful finite search is reused. Auth/network/protocol failures never poison the cache.
            if (token.isNotBlank()) { cached = result; cachedKey = key; cachedAt = monotonicMillis() }
            result
        } catch (error: CancellationException) { limits += "CANCELLED"; lastReport = report(); throw error }
        catch (error: Exception) {
            limits += "ERROR"; lastReport = report()
            if (error is OpenCellLookupException) throw error
            throw OpenCellLookupException()
        }
    }

    private fun parse(source: JSONObject, technology: String, lat: Double, lon: Double, now: Long, box: OpenCellBox,
        preferred: RemoteCellRadio?, reject: (String) -> Unit): OpenCellRecord? {
        fun invalid(reason: String): OpenCellRecord? { reject(reason); return null }
        if (technology !in setOf("LTE", "NR", "GSM", "UMTS", "CDMA", "NBIOT")) return invalid("UNSUPPORTED_RADIO")
        if (preferred != null && technology != preferred.name) return invalid("RADIO_FILTER")
        val mcc = integer(source, "mcc") ?: return invalid("INVALID_IDENTITY")
        val mnc = integer(source, "mnc", "net") ?: return invalid("INVALID_IDENTITY")
        val area = listOf("tac", "lac", "area").mapNotNull { integer(source, it) }.firstOrNull { it > 0 }
            ?: integer(source, "tac", "lac", "area") ?: return invalid("INVALID_IDENTITY")
        val id = integer(source, "cellid", "cell", "ci", "cid", "nci") ?: return invalid("INVALID_IDENTITY")
        if (mcc !in 1..999 || mnc !in 0..999 || area !in 0..16_777_215 || id <= 0) return invalid("INVALID_IDENTITY")
        if (technology == "LTE" || technology == "NR") {
            try { RemoteCellIdentity(RemoteCellRadio.valueOf(technology), mcc.toInt(), mnc.toInt(), area.toInt(), id).validate() }
            catch (_: IllegalArgumentException) { return invalid("INVALID_IDENTITY") }
        } else if (id > 268_435_455L || area > 65_535) return invalid("INVALID_IDENTITY")
        val pointLat = source.optDouble("lat", Double.NaN); val pointLon = source.optDouble("lon", Double.NaN)
        if (pointLat !in -90.0..90.0 || pointLon !in -180.0..180.0) return invalid("INVALID_POSITION")
        if (!box.contains(pointLat, pointLon)) return invalid("OUTSIDE_BOX")
        val distance = GeoMath.distance(LatLng(lat, lon), LatLng(pointLat, pointLon))
        if (distance > policy.maxDistanceMeters) return invalid("OUTSIDE_RADIUS")
        val updated = integer(source, "updated")?.takeIf { it > 0 }?.let {
            if (it > Long.MAX_VALUE / 1000) return invalid("INVALID_UPDATED")
            // Documented timestamps are seconds; accept milliseconds without using fetchedAt as last-seen.
            if (it >= 1_000_000_000_000L) it else it * 1000
        }
        if (updated != null && updated > now + 300_000L) return invalid("FUTURE_UPDATED")
        if (updated != null && now - updated > policy.maxAgeMillis) return invalid("STALE")
        return OpenCellRecord(technology, mcc.toInt(), mnc.toInt(), area.toInt(), id, pointLat, pointLon, distance,
            updated, integer(source, "samples")?.takeIf { it in 1..Int.MAX_VALUE }?.toInt(),
            if (updated == null) OpenCellFreshness.UNKNOWN else OpenCellFreshness.RECENT, source.toString())
    }

    private suspend fun fetch(request: Request): ByteArray = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(OpenCellLookupException()) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val bytes = response.use {
                        if (!it.isSuccessful) throw OpenCellLookupException(httpStatus = it.code)
                        val body = it.body ?: throw OpenCellLookupException()
                        val result = java.io.ByteArrayOutputStream()
                        body.byteStream().use { input ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val size = input.read(buffer); if (size < 0) break
                                if (result.size() + size > 2_097_152) throw OpenCellLookupException()
                                result.write(buffer, 0, size)
                            }
                        }
                        result.toByteArray()
                    }
                    if (continuation.isActive) continuation.resume(bytes)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error as? OpenCellLookupException ?: OpenCellLookupException())
                }
            }
        })
    }

    companion object {
        internal val recordOrder = compareBy<OpenCellRecord> { it.freshness.ordinal }
            .thenBy { it.distanceMeters }.thenByDescending { it.updatedAtMillis ?: 0 }.thenByDescending { it.samples ?: 0 }
        internal fun normalizeRadio(value: String): String = when (val upper = value.uppercase(Locale.ROOT)) {
            "WCDMA" -> "UMTS"; "5G", "NR5G" -> "NR"; else -> upper
        }
        private fun integer(source: JSONObject, vararg keys: String): Long? = keys.firstNotNullOfOrNull { key ->
            source.opt(key)?.takeUnless { it == JSONObject.NULL }?.toString()?.toLongOrNull()
        }
    }
}
