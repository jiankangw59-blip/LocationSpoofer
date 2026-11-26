package com.vincenthzr.locationspoofer.data.repository

import android.util.AtomicFile
import com.vincenthzr.locationspoofer.data.model.RemoteWifiPoint
import com.vincenthzr.locationspoofer.data.model.RemoteWifiRegion
import com.vincenthzr.locationspoofer.data.model.RemoteWifiSnapshot
import com.vincenthzr.locationspoofer.data.model.RemoteWifiMetadata
import com.vincenthzr.locationspoofer.data.model.RemoteWifiMetadataStatus
import com.vincenthzr.locationspoofer.utils.WifiMetadataEnricher
import com.vincenthzr.locationspoofer.utils.WifiMetadataCodec
import com.vincenthzr.locationspoofer.utils.AppleWifiTileClient
import com.vincenthzr.locationspoofer.utils.AppleWifiTileCodec
import com.vincenthzr.locationspoofer.utils.AppleWifiDiscovery
import com.vincenthzr.locationspoofer.utils.AppleWifiSearchReport
import com.vincenthzr.locationspoofer.data.model.RemoteWifiSource
import com.vincenthzr.locationspoofer.utils.CoordinateUtils.LatLng
import com.vincenthzr.locationspoofer.utils.GeoMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

class RemoteWifiCacheException(cause: Throwable) : IOException("Unable to save Wi-Fi query", cause)

/** Stores the latest preview separately from simulation and collected environment records. */
class RemoteWifiRepository(
    private val client: AppleWifiTileClient,
    cacheFile: File,
    private val metadataEnricher: WifiMetadataEnricher? = null,
    private val discovery: AppleWifiDiscovery? = null,
    statusFile: File? = null
) {
    private val cache = AtomicFile(cacheFile)
    private val statusCache = statusFile?.let(::AtomicFile)
    private val mutex = Mutex()
    val lastSearchReport: AppleWifiSearchReport? get() = discovery?.lastReport

    suspend fun query(latitude: Double, longitude: Double, region: RemoteWifiRegion): RemoteWifiSnapshot =
        mutex.withLock {
            AppleWifiTileCodec.tileKey(latitude, longitude)
            try {
            val raw = withContext(Dispatchers.IO) {
                discovery?.query(latitude, longitude, region) ?: client.query(latitude, longitude, region)
            }
            val cached = withContext(Dispatchers.IO) {
                try { readCache() } catch (error: kotlinx.coroutines.CancellationException) { throw error }
                catch (_: Exception) { null }
            }
            val snapshot = withContext(Dispatchers.IO) { metadataEnricher?.enrich(raw, cached) ?: raw }
            withContext(Dispatchers.IO) { save(snapshot) }
            snapshot
            } finally {
                val report = discovery?.lastReport
                if (report != null && statusCache != null) withContext(NonCancellable + Dispatchers.IO) {
                    try {
                        val payload = JSONObject().put("version", 1).put("latitude", latitude).put("longitude", longitude)
                            .put("region", region.name).put("queried_at", System.currentTimeMillis()).put("report", report.toJson())
                        val stream = statusCache.startWrite()
                        try { stream.write(payload.toString().toByteArray(Charsets.UTF_8)); statusCache.finishWrite(stream) }
                        catch (error: Exception) { statusCache.failWrite(stream); throw error }
                    } catch (_: Exception) { android.util.Log.w("LocationSpoofer", "Unable to save wireless lookup status") }
                }
            }
        }

    suspend fun loadSearchReport(latitude: Double, longitude: Double, region: RemoteWifiRegion): AppleWifiSearchReport? =
        mutex.withLock { withContext(Dispatchers.IO) {
            val file = statusCache ?: return@withContext null
            if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@withContext null
            val payload = file.openRead().use { input ->
                require(input.channel.size() <= 262_144); JSONObject(input.readBytes().toString(Charsets.UTF_8))
            }
            require(payload.getInt("version") == 1 && payload.getLong("queried_at") > 0)
            if (payload.getString("region") != region.name) return@withContext null
            val point = LatLng(payload.getDouble("latitude"), payload.getDouble("longitude"))
            require(point.lat in -85.05112878..85.05112878 && point.lng in -180.0..180.0)
            if (GeoMath.distance(point, LatLng(latitude, longitude)) > 2.0) return@withContext null
            AppleWifiSearchReport.fromJson(payload.getJSONObject("report"))
        } }

    suspend fun loadLatest(): RemoteWifiSnapshot? = mutex.withLock {
        withContext(Dispatchers.IO) { readCache() }
    }

    private fun readCache(): RemoteWifiSnapshot? {
        if (!cache.baseFile.exists() && !File(cache.baseFile.path + ".bak").exists()) {
            return null
        }
        val payload = cache.openRead().use { input ->
            require(input.channel.size() <= 2 * 1024 * 1024) { "Invalid Wi-Fi cache size" }
            input.readBytes().toString(Charsets.UTF_8)
        }
        val json = JSONObject(payload)
        val version = json.getInt("version")
        require(version in 1..3 && json.getString("coordinate_system") == "WGS84")
        val latitude = json.getDouble("latitude")
        val longitude = json.getDouble("longitude")
        val tileKey = AppleWifiTileCodec.tileKey(latitude, longitude)
        require(json.getLong("tile_key") == tileKey)
        val region = RemoteWifiRegion.valueOf(json.getString("region"))
        val fetchedAt = json.getLong("fetched_at")
        require(fetchedAt > 0)
        val array = json.getJSONArray("points")
        require(array.length() in 1..100)
        val target = LatLng(latitude, longitude)
        val points = (0 until array.length()).map { index ->
            val entry = array.getJSONObject(index)
            val lat = entry.getDouble("latitude")
            val lng = entry.getDouble("longitude")
            val bssid = entry.getString("bssid")
            require(lat in -90.0..90.0 && lng in -180.0..180.0)
            require(bssid.matches(Regex("[0-9a-f]{2}(:[0-9a-f]{2}){5}")))
            val distance = GeoMath.distance(target, LatLng(lat, lng))
            require(distance in 0.0..AppleWifiTileCodec.RADIUS_METERS)
            val metadata = if (version >= 2 && entry.has("metadata")) {
                val value = entry.getJSONObject("metadata")
                require(value.getString("source") == "WiGLE")
                val ssid = value.optString("ssid").takeIf { value.has("ssid") && !value.isNull("ssid") }
                require(ssid == null || ssid.toByteArray(Charsets.UTF_8).size <= 32)
                val frequency = value.optInt("frequency_mhz", 0).takeIf { value.has("frequency_mhz") }
                require(frequency == null || WifiMetadataCodec.frequencyToChannel(frequency) != null)
                val channel = value.optInt("channel", 0).takeIf { value.has("channel") }
                require(channel == null || channel in 1..233)
                require(frequency == null || channel == null || WifiMetadataCodec.frequencyToChannel(frequency) == channel)
                val encryption = value.optString("encryption").takeIf { value.has("encryption") }
                require(encryption == null || encryption.length <= 64)
                RemoteWifiMetadata(ssid, frequency, channel, encryption, value.getLong("fetched_at").also { require(it > 0) },
                    value.optLong("observed_at", 0).takeIf { it > 0 })
            } else null
            RemoteWifiPoint(bssid, lat, lng, distance, metadata)
        }
        val adopted = discovery?.let { lookup -> points.filter { it.distanceMeters <= lookup.adoptionRadiusMeters } } ?: points
        if (adopted.isEmpty()) return null
        return RemoteWifiSnapshot(latitude, longitude, region, tileKey, fetchedAt, adopted,
            RemoteWifiMetadataStatus.valueOf(json.optString("metadata_status", "NOT_REQUESTED")),
            json.optInt("metadata_http_status", 0).takeIf { it in 100..599 },
            RemoteWifiSource.valueOf(json.optString("source_type", "APPLE_TILE")))
    }

    private fun save(snapshot: RemoteWifiSnapshot) {
        val points = JSONArray()
        snapshot.points.forEach { point ->
            points.put(JSONObject().apply {
                put("bssid", point.bssid)
                put("latitude", point.latitude)
                put("longitude", point.longitude)
                put("distance_meters", point.distanceMeters)
                point.metadata?.let { metadata ->
                    put("metadata", JSONObject().apply {
                        put("source", "WiGLE")
                        putOpt("ssid", metadata.ssid)
                        putOpt("frequency_mhz", metadata.frequencyMHz)
                        putOpt("channel", metadata.channel)
                        putOpt("encryption", metadata.encryption)
                        put("fetched_at", metadata.fetchedAt)
                        putOpt("observed_at", metadata.observedAt)
                    })
                }
            })
        }
        val json = JSONObject().apply {
            put("version", 3)
            put("source", if (snapshot.source == RemoteWifiSource.APPLE_WLOC) "Apple WLOC" else "Apple Tile")
            put("source_type", snapshot.source.name)
            put("coordinate_system", "WGS84")
            put("latitude", snapshot.latitude)
            put("longitude", snapshot.longitude)
            put("region", snapshot.region.name)
            put("tile_key", snapshot.tileKey)
            put("fetched_at", snapshot.fetchedAt)
            put("points", points)
            put("metadata_status", snapshot.metadataStatus.name)
            putOpt("metadata_http_status", snapshot.metadataHttpStatus)
        }
        val stream = try {
            cache.startWrite()
        } catch (error: IOException) {
            throw RemoteWifiCacheException(error)
        }
        try {
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            cache.finishWrite(stream)
        } catch (error: Exception) {
            cache.failWrite(stream)
            throw RemoteWifiCacheException(error)
        }
    }
}
