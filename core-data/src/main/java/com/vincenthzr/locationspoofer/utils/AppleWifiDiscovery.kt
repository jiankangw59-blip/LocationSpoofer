package com.vincenthzr.locationspoofer.utils

import com.vincenthzr.locationspoofer.data.model.*
import com.vincenthzr.locationspoofer.utils.CoordinateUtils.LatLng
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.UnknownHostException
import kotlin.math.cos

data class AppleWifiSearchPolicy(
    val finalRadiusMeters: Double = 300.0,
    val seedRadiusMeters: Double = 12_000.0,
    val maxTileRequests: Int = 13,
    val maxWlocRequests: Int = 10,
    val maxHops: Int = 5,
    val timeBudgetMillis: Long = 55_000
) {
    init {
        require(finalRadiusMeters in 1.0..500.0 && seedRadiusMeters in 8_000.0..12_000.0)
        require(maxTileRequests in 1..13 && maxWlocRequests in 1..10 && maxHops in 1..5)
        require(timeBudgetMillis in 1..55_000)
    }
}

data class AppleWifiSearchReport(
    val region: RemoteWifiRegion, val tileRequests: Int, val wigleRequests: Int, val wlocRequests: Int,
    val wigleRawRecords: Int, val wigleStaleRecords: Int, val wigleInvalidRecords: Int,
    val nearestRecordedMeters: Double?, val acceptedRecords: Int, val finalRadiusMeters: Double,
    val hops: List<JSONObject>, val limits: Set<String>, val outcome: String, val elapsedMillis: Long,
    val wiglePages: List<JSONObject> = emptyList(), val wigleFilterViolations: Int = 0
) {
    fun toJson(): JSONObject = JSONObject().put("region", region.name).put("tile_requests", tileRequests)
        .put("wigle_requests", wigleRequests).put("wloc_requests", wlocRequests)
        .put("wigle_raw_records", wigleRawRecords).put("wigle_stale_records", wigleStaleRecords)
        .put("wigle_invalid_records", wigleInvalidRecords).putOpt("nearest_recorded_meters", nearestRecordedMeters)
        .put("accepted_records", acceptedRecords).put("final_radius_meters", finalRadiusMeters)
        .put("hops", JSONArray(hops)).put("limits", JSONArray(limits.sorted()))
        .put("outcome", outcome).put("elapsed_millis", elapsedMillis)
        .put("wifi_coverage", if (acceptedRecords > 0) "NEAR_WIFI_CONFIRMED" else "UNKNOWN")
        .put("wigle_pages", JSONArray(wiglePages)).put("wigle_filter_violations", wigleFilterViolations)

    companion object {
        fun fromJson(json: JSONObject): AppleWifiSearchReport {
            fun count(key: String, maximum: Int): Int = json.getInt(key).also { require(it in 0..maximum) }
            fun entries(key: String, maximum: Int): List<JSONObject> {
                val array = json.optJSONArray(key) ?: JSONArray()
                require(array.length() <= maximum)
                return (0 until array.length()).map { JSONObject(array.getJSONObject(it).toString()) }
            }
            val flags = json.getJSONArray("limits").also { require(it.length() <= 32) }
            val limits = (0 until flags.length()).map { flags.getString(it).also { value -> require(value.matches(Regex("[A-Z_]{1,64}"))) } }.toSet()
            val nearest = json.optDouble("nearest_recorded_meters", Double.NaN).takeIf { it.isFinite() }
            require(nearest == null || nearest in 0.0..12_000.0)
            return AppleWifiSearchReport(RemoteWifiRegion.valueOf(json.getString("region")), count("tile_requests", 13),
                count("wigle_requests", 6), count("wloc_requests", 10), count("wigle_raw_records", 600),
                count("wigle_stale_records", 600), count("wigle_invalid_records", 600), nearest, count("accepted_records", 100),
                json.getDouble("final_radius_meters").also { require(it in 1.0..500.0) }, entries("hops", 10), limits,
                json.getString("outcome").also { require(it.matches(Regex("[A-Za-z_]{1,100}"))) },
                json.getLong("elapsed_millis").also { require(it in 0..600_000) }, entries("wigle_pages", 6),
                json.optInt("wigle_filter_violations", 0).also { require(it in 0..600) })
        }
    }
}
class RemoteWifiSearchIncompleteException : IOException("Wi-Fi discovery stopped at a resource limit")

/** Read-only cold start. Only original Apple coordinates within the adoption radius can leave this class. */
class AppleWifiDiscovery(
    tiles: AppleWifiTileClient = AppleWifiTileClient(),
    wloc: AppleWifiWlocClient = AppleWifiWlocClient(),
    private val wigle: WigleSeedSource = WigleSeedSource(),
    private val tokenProvider: () -> String = { "" },
    private val localSeeds: suspend (Double, Double) -> List<String> = { _, _ -> emptyList() },
    private val policy: AppleWifiSearchPolicy = AppleWifiSearchPolicy(),
    private val tileLookup: suspend (Long, Double, Double, RemoteWifiRegion, Double) -> List<RemoteWifiPoint> = tiles::seedTile,
    private val wlocLookup: suspend (List<String>, Double, Double, RemoteWifiRegion, Double) -> List<RemoteWifiPoint> = wloc::query,
    private val wigleLookup: (suspend (Double, Double, Double, String, Int) -> WigleSeedResult)? = null
) {
    val adoptionRadiusMeters: Double get() = policy.finalRadiusMeters
    @Volatile var lastReport: AppleWifiSearchReport? = null
        private set

    suspend fun query(latitude: Double, longitude: Double, region: RemoteWifiRegion): RemoteWifiSnapshot {
        val tileKey = AppleWifiTileCodec.tileKey(latitude, longitude)
        val started = System.nanoTime()
        var tileRequests = 0; var wigleRequests = 0; var wlocRequests = 0
        var raw = 0; var stale = 0; var invalid = 0
        var filterViolations = 0; val wiglePages = mutableListOf<JSONObject>()
        val asked = mutableSetOf<String>(); val askedIndividually = mutableSetOf<String>()
        val points = linkedMapOf<String, RemoteWifiPoint>()
        val metadata = mutableMapOf<String, RemoteWifiMetadata>(); val hops = mutableListOf<JSONObject>()
        val limits = linkedSetOf<String>()
        var outcome = "NO_NEARBY_RECORDS"
        fun recordWigleResult(radius: Double, discovered: WigleSeedResult) {
            wigleRequests += discovered.requests; raw += discovered.rawRecords
            stale += discovered.staleRecords; invalid += discovered.invalidRecords
            filterViolations += discovered.filterViolations
            wiglePages += discovered.pages.map { JSONObject(it.toString()).put("radius_meters", radius) }
            limits += discovered.limits.map { "WIGLE_$it" }
            // A repeated/short WiGLE page is not proof of a complete geographical inventory.
            if (discovered.repeatedPage) limits += "WIGLE_REPEATED_PAGE"
            if (discovered.truncated) limits += "WIGLE_PAGE_LIMIT"
        }
        fun accept(): List<RemoteWifiPoint> = points.values.filter { it.distanceMeters <= policy.finalRadiusMeters }
            .sortedBy { it.distanceMeters }.take(100).map { it.copy(metadata = metadata[it.bssid] ?: it.metadata) }
        fun snapshot(source: RemoteWifiSource) = RemoteWifiSnapshot(latitude, longitude, region, tileKey,
            System.currentTimeMillis(), accept(), source = source)
        fun retain(values: List<RemoteWifiPoint>) {
            for (point in values) {
                val address = WifiBssid.normalize(point.bssid) ?: continue
                if (point.latitude !in -90.0..90.0 || point.longitude !in -180.0..180.0) continue
                // Never trust a stored/provider-supplied distance or move a seed to the target.
                val distance = GeoMath.distance(LatLng(latitude, longitude), LatLng(point.latitude, point.longitude))
                if (distance <= policy.seedRadiusMeters) points[address] = point.copy(bssid = address, distanceMeters = distance)
            }
            if (points.size > 1000) {
                val nearest = points.values.sortedBy { it.distanceMeters }.take(1000)
                points.clear(); nearest.forEach { points[it.bssid] = it }
            }
        }
        suspend fun walk(seeds: List<WifiSeed>, source: String): RemoteWifiSnapshot? {
            seeds.forEach { seed -> seed.metadata?.let { metadata[seed.bssid] = it } }
            repeat(2) {
                var batch = seeds.sortedWith(WigleSeedSource.order).mapNotNull { WifiBssid.normalize(it.bssid) }
                    .distinct().filter { it !in asked }.take(6)
                if (batch.isEmpty()) return null
                var prior = Double.POSITIVE_INFINITY
                for (hop in 0 until policy.maxHops) {
                    if (wlocRequests >= policy.maxWlocRequests) { limits += "WLOC_REQUEST_LIMIT"; return null }
                    if (batch.size == 1) askedIndividually += batch.first()
                    asked += batch; wlocRequests++
                    val returned = wlocLookup(batch, latitude, longitude, region, policy.seedRadiusMeters)
                    retain(returned)
                    val valid = returned.mapNotNull { point ->
                        val address = WifiBssid.normalize(point.bssid) ?: return@mapNotNull null
                        points[address]
                    }.distinctBy { it.bssid }.sortedBy { it.distanceMeters }
                    val nearest = valid.firstOrNull()
                    val entry = JSONObject().put("seed_source", source).put("hop", hop + 1).put("seed_count", batch.size)
                        .put("valid_positions", valid.size).putOpt("nearest_meters", nearest?.distanceMeters)
                        .putOpt("best_recorded_meters", points.values.minOfOrNull { it.distanceMeters })
                    if (accept().isNotEmpty()) {
                        entry.put("status", "WITHIN_ADOPTION_RADIUS"); hops += entry
                        return snapshot(RemoteWifiSource.APPLE_WLOC)
                    }
                    if (nearest == null) { entry.put("status", "NO_RECORDED_POSITION"); hops += entry; break }
                    if (nearest.bssid in askedIndividually || nearest.distanceMeters >= prior - 1.0) {
                        entry.put("status", "NO_PROGRESS"); hops += entry; break
                    }
                    entry.put("status", "CLOSER"); hops += entry
                    prior = nearest.distanceMeters
                    batch = listOf(nearest.bssid)
                    if (hop + 1 == policy.maxHops) limits += "HOP_LIMIT"
                }
            }
            return null
        }
        try {
            val result = withTimeoutOrNull(policy.timeBudgetMillis) {
                val tileKeys = seedTileKeys(latitude, longitude).take(policy.maxTileRequests)
                for ((index, key) in tileKeys.withIndex()) {
                    tileRequests++
                    var tileDnsFailure: UnknownHostException? = null
                    val found = try {
                        tileLookup(key, latitude, longitude, region, policy.seedRadiusMeters)
                    } catch (error: UnknownHostException) {
                        if (index != 0) throw error
                        // The saved seeds use WLOC, which has a different host from the tiles.
                        tileDnsFailure = error
                        emptyList()
                    }
                    retain(found)
                    if (accept().isNotEmpty()) return@withTimeoutOrNull snapshot(RemoteWifiSource.APPLE_TILE)
                    if (found.isNotEmpty()) walk(found.map { WifiSeed(it.bssid, it.distanceMeters) }, "APPLE_TILE")
                        ?.let { return@withTimeoutOrNull it }
                    if (index == 0) {
                        val saved = localSeeds(latitude, longitude).mapNotNull { WifiBssid.normalize(it) }.distinct().take(12)
                        walk(saved.map { WifiSeed(it, null) }, "SAVED_REMOTE_RECORD")?.let { return@withTimeoutOrNull it }
                    }
                    tileDnsFailure?.let { throw it }
                }
                val token = tokenProvider().trim()
                if (token.isNotEmpty()) {
                    val found = wigle.withSession { scoped ->
                        for (radius in listOf(1_000.0, 3_000.0, 8_000.0)) {
                            val discovered = wigleLookup?.invoke(latitude, longitude, radius, token, 2)
                                ?: scoped.query(latitude, longitude, radius, token, 2) { interrupted ->
                                    recordWigleResult(radius, interrupted)
                                }
                            recordWigleResult(radius, discovered)
                            walk(discovered.seeds, "WIGLE_${radius.toInt()}M")?.let { return@withSession it }
                        }
                        null
                    }
                    found?.let { return@withTimeoutOrNull it }
                }
                if (limits.isNotEmpty()) throw RemoteWifiSearchIncompleteException()
                throw RemoteWifiNoCoverageException()
            }
            if (result == null) { limits += "TIME_LIMIT"; throw RemoteWifiTimeoutException() }
            outcome = result.source.name
            return result
        } catch (error: CancellationException) { outcome = "CANCELLED"; throw error }
        catch (error: Exception) {
            outcome = when (error) {
                is RemoteWifiNoCoverageException -> "NO_NEARBY_RECORDS"
                is RemoteWifiSearchIncompleteException, is RemoteWifiTimeoutException -> "INCOMPLETE"
                else -> "ERROR_${error.javaClass.simpleName}"
            }
            throw error
        } finally {
            lastReport = AppleWifiSearchReport(region, tileRequests, wigleRequests, wlocRequests, raw, stale, invalid,
                points.values.minOfOrNull { it.distanceMeters }, accept().size, policy.finalRadiusMeters, hops.toList(),
                limits.toSet(), outcome, (System.nanoTime() - started) / 1_000_000, wiglePages.toList(), filterViolations)
        }
    }

    companion object {
        internal fun seedTileKeys(latitude: Double, longitude: Double): List<Long> {
            val latStep = Math.toDegrees(8_000.0 / GeoMath.EARTH_RADIUS_M)
            val lonStep = latStep / cos(Math.toRadians(latitude))
            fun wrap(lon: Double) = ((lon + 540.0) % 360.0) - 180.0
            return (AppleWifiTileCodec.searchTileKeys(latitude, longitude) +
                listOf(latitude + latStep to longitude, latitude - latStep to longitude,
                    latitude to wrap(longitude + lonStep), latitude to wrap(longitude - lonStep))
                    .filter { it.first in -AppleWifiTileCodec.MAX_LATITUDE..AppleWifiTileCodec.MAX_LATITUDE }
                    .map { AppleWifiTileCodec.tileKey(it.first, it.second) }).distinct()
        }
    }
}
