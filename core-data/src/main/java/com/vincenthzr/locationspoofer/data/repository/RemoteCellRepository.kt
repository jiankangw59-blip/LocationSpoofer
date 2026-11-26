package com.vincenthzr.locationspoofer.data.repository

import android.util.AtomicFile
import com.vincenthzr.locationspoofer.data.db.EnvironmentDao
import com.vincenthzr.locationspoofer.data.model.*
import com.vincenthzr.locationspoofer.utils.*
import com.vincenthzr.locationspoofer.utils.CoordinateUtils.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class RemoteCellRepository(
    private val client: AppleCellClient, private val dao: EnvironmentDao,
    private val settings: SettingsRepository, cacheFile: File
) {
    private val cache = AtomicFile(cacheFile)
    private val mutex = Mutex()
    @Volatile private var latestSnapshot: RemoteCellSnapshot? = null

    /** Already loaded data only; serialization of a position must not perform disk/network I/O. */
    fun peekLatest(): RemoteCellSnapshot? = latestSnapshot

    suspend fun query(lat: Double, lng: Double, region: RemoteWifiRegion, explicitSeed: RemoteCellIdentity? = null,
        preferredRadio: RemoteCellRadio? = null): RemoteCellSnapshot = mutex.withLock {
        require(lat.isFinite() && lng.isFinite() && lat in -85.05112878..85.05112878 && lng in -180.0..180.0)
        require(explicitSeed == null || preferredRadio == null || explicitSeed.radio == preferredRadio)
        val snapshot = if (explicitSeed != null) client.query(lat, lng, region, explicitSeed) else {
            // A stale local seed must not permanently block fresh remote discovery.
            // Retry only a genuine no-coverage response, never auth/network/protocol failures.
            var found: RemoteCellSnapshot? = null
            var noCoverage: RemoteCellNoCoverageException? = null
            val tried = linkedSetOf<RemoteCellIdentity>()
            for (seed in localSeeds(lat, lng, preferredRadio).take(2)) {
                tried.add(seed)
                try { found = client.query(lat, lng, region, seed); break }
                catch (error: RemoteCellNoCoverageException) { noCoverage = error }
            }
            if (found == null) {
                // Discovery is cached across attempts; only genuine WLOC misses advance to another seed.
                for (attempt in 0 until 3) {
                    val seed = client.findSeed(lat, lng, settings.getOpencellidApiToken(), preferredRadio, tried) ?: break
                    tried += seed
                    try { found = client.query(lat, lng, region, seed); break }
                    catch (error: RemoteCellNoCoverageException) { noCoverage = error }
                }
                if (found == null) throw (noCoverage ?: RemoteCellNoSeedException())
            }
            checkNotNull(found)
        }
        withContext(Dispatchers.IO) {
            val stream = try { cache.startWrite() }
                catch (error: java.io.IOException) { throw RemoteWifiCacheException(error) }
            try { stream.write(encode(snapshot).toString().toByteArray()); cache.finishWrite(stream) }
            catch (error: Exception) { cache.failWrite(stream); throw RemoteWifiCacheException(error) }
        }
        latestSnapshot = snapshot
        snapshot
    }

    suspend fun loadLatest(): RemoteCellSnapshot? = mutex.withLock { withContext(Dispatchers.IO) {
        if (!cache.baseFile.exists() && !File(cache.baseFile.path + ".bak").exists()) return@withContext null
        val json = cache.openRead().use { input ->
            require(input.channel.size() <= 2_097_152)
            JSONObject(input.readBytes().toString(Charsets.UTF_8))
        }
        require(json.getInt("version") == 1 && json.getString("coordinate_system") == "WGS84")
        val lat = json.getDouble("latitude"); val lng = json.getDouble("longitude")
        require(lat in -85.05112878..85.05112878 && lng in -180.0..180.0)
        val points = json.getJSONArray("points")
        require(points.length() in 1..100)
        RemoteCellSnapshot(lat, lng, RemoteWifiRegion.valueOf(json.getString("region")), identity(json.getJSONObject("seed")),
            json.getLong("fetched_at").also { require(it > 0) }, (0 until points.length()).map { index ->
                val point = points.getJSONObject(index)
                val pointLat = point.getDouble("latitude"); val pointLng = point.getDouble("longitude")
                require(pointLat in -90.0..90.0 && pointLng in -180.0..180.0)
                val distance = GeoMath.distance(LatLng(lat, lng), LatLng(pointLat, pointLng))
                require(distance <= 20_000.0)
                RemoteCellPoint(identity(point), pointLat, pointLng, distance)
            }).also { latestSnapshot = it }
    } }

    private suspend fun localSeeds(lat: Double, lng: Double, preferredRadio: RemoteCellRadio?): List<RemoteCellIdentity> {
        val gcj = CoordinateUtils.wgs84ToGcj02(lat, lng)
        val records = dao.getNearestLocations(gcj.lat, gcj.lng, 10)
        return records.filter { GeoMath.distance(gcj, LatLng(it.location.lat, it.location.lng)) <= 20_000.0 }
            .flatMap { it.cells }.mapNotNull { entry ->
                val cell = entry.device
                val radio = when (cell.type) { "LTE" -> RemoteCellRadio.LTE; "NR" -> RemoteCellRadio.NR; else -> return@mapNotNull null }
                if (preferredRadio != null && radio != preferredRadio) return@mapNotNull null
                val seed = RemoteCellIdentity(radio, cell.mcc, cell.mnc, cell.tac, if (radio == RemoteCellRadio.LTE) cell.ci.toLong() else cell.nci)
                try { seed.validate(); seed } catch (_: IllegalArgumentException) { null }
            }.distinct()
    }

    private fun identity(json: JSONObject): RemoteCellIdentity = RemoteCellIdentity(RemoteCellRadio.valueOf(json.getString("radio")),
        json.getInt("mcc"), json.getInt("mnc"), json.getInt("tac"), json.getLong("id")).also { it.validate() }

    private fun encode(snapshot: RemoteCellSnapshot): JSONObject {
        fun encodeIdentity(value: RemoteCellIdentity) = JSONObject().put("radio", value.radio.name)
            .put("mcc", value.mcc).put("mnc", value.mnc).put("tac", value.tac).put("id", value.id)
        return JSONObject().put("version", 1).put("source", "Apple WLOC").put("coordinate_system", "WGS84")
            .put("latitude", snapshot.latitude).put("longitude", snapshot.longitude).put("region", snapshot.region.name)
            .put("seed", encodeIdentity(snapshot.seed)).put("fetched_at", snapshot.fetchedAt)
            .put("points", JSONArray().apply { snapshot.points.forEach { point ->
                put(encodeIdentity(point.identity).put("latitude", point.latitude).put("longitude", point.longitude))
            } })
    }
}
