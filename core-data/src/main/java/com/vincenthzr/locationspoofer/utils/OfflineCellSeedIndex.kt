package com.vincenthzr.locationspoofer.utils

import android.database.sqlite.SQLiteDatabase
import android.util.AtomicFile
import com.vincenthzr.locationspoofer.data.model.RemoteCellIdentity
import com.vincenthzr.locationspoofer.data.model.RemoteCellRadio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.math.cos
import kotlin.math.floor

data class OfflineCellIndexInfo(val mcc: Int, val rows: Int, val importedAt: Long, val sourceSha256: String)
data class OfflineCellCandidate(val identity: RemoteCellIdentity, val latitude: Double, val longitude: Double,
    val distanceMeters: Double, val updatedAt: Long)
data class OfflineCellSearchResult(val candidates: List<OfflineCellCandidate>, val report: JSONObject)

/** Private read-only data index. No hardware scans, resident connection, polling or external service. */
class OfflineCellSeedIndex(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val mutex = Mutex()
    private val atomic = AtomicFile(file)
    private var cachedKey: String? = null
    private var cachedAt = 0L
    private var cached: OfflineCellSearchResult? = null
    @Volatile var lastReport: JSONObject? = null
        private set

    private fun open(path: File = file): SQLiteDatabase = SQLiteDatabase.openDatabase(path.path, null,
        SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)

    private fun info(db: SQLiteDatabase): OfflineCellIndexInfo {
        require(db.version == 1) { "Unsupported cell index version" }
        val metadata = db.rawQuery("SELECT key,value FROM metadata", null).use { cursor ->
            buildMap { while (cursor.moveToNext()) { require(size < 32); put(cursor.getString(0), cursor.getString(1)) } }
        }
        require(metadata["format"] == "locationspoofer-lte-index-v1" && metadata["source"] == "OpenCellID" &&
            metadata["radio"] == "LTE" && metadata["grid_degrees"] == "0.01") { "Unsupported cell index format" }
        val mcc = checkNotNull(metadata["mcc"]).toInt(); val rows = checkNotNull(metadata["rows"]).toInt()
        val imported = checkNotNull(metadata["imported_at"]).toLong(); val hash = checkNotNull(metadata["source_sha256"])
        require(mcc in 1..999 && rows in 1..2_000_000 && imported > 0 && hash.matches(Regex("[a-f0-9]{64}")))
        db.rawQuery("SELECT mcc,mnc,tac,ci,lat,lon,updated,grid_lat,grid_lon FROM cells LIMIT 0", null).close()
        return OfflineCellIndexInfo(mcc, rows, imported, hash)
    }

    suspend fun status(): OfflineCellIndexInfo? = mutex.withLock { withContext(Dispatchers.IO) {
        if (!file.exists()) null else open().use(::info)
    } }

    /** SAF/caller owns the input stream. Validate a staging database before replacing any working index. */
    suspend fun install(input: InputStream): OfflineCellIndexInfo = mutex.withLock { withContext(Dispatchers.IO) {
        checkNotNull(file.parentFile).mkdirs()
        val staging = File(file.parentFile, "offline-cells-${UUID.randomUUID()}.building")
        try {
            staging.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024); var size = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer); if (count < 0) break
                    size += count; require(size <= 128L * 1024 * 1024) { "Cell index exceeds 128 MiB" }
                    output.write(buffer, 0, count)
                }
            }
            val verified = open(staging).use { db ->
                val metadata = info(db)
                db.rawQuery("PRAGMA quick_check", null).use { require(it.moveToFirst() && it.getString(0) == "ok") }
                db.rawQuery("SELECT COUNT(*) FROM cells", null).use { require(it.moveToFirst() && it.getInt(0) == metadata.rows) }
                metadata
            }
            coroutineContext.ensureActive()
            val output = atomic.startWrite()
            try { staging.inputStream().use { it.copyTo(output) }; atomic.finishWrite(output) }
            catch (error: Exception) { atomic.failWrite(output); throw error }
            cachedKey = null; cached = null; lastReport = null
            verified
        } finally { staging.delete() }
    } }

    suspend fun search(latitude: Double, longitude: Double, radio: RemoteCellRadio? = null): OfflineCellSearchResult =
        mutex.withLock { withContext(Dispatchers.IO) {
            require(latitude.isFinite() && longitude.isFinite() && latitude in -85.05112878..85.05112878 && longitude in -180.0..180.0)
            val key = "$latitude:$longitude:$radio:${file.lastModified()}:${file.length()}"
            val now = clock()
            if (key == cachedKey && now - cachedAt in 0..120_000 && cached != null) {
                val hit = cached!!.copy(report = JSONObject(cached!!.report.toString()).put("from_cache", true))
                lastReport = hit.report; return@withContext hit
            }
            val started = System.nanoTime(); val stages = JSONArray(); val limits = linkedSetOf<String>()
            val points = linkedMapOf<RemoteCellIdentity, OfflineCellCandidate>()
            var rejected = 0; var available = false; var index: OfflineCellIndexInfo? = null
            if (file.exists() && radio != RemoteCellRadio.NR) open().use { db ->
                available = true; index = info(db)
                val normalizedLon = if (longitude == 180.0) -180.0 else longitude
                for (radius in listOf(1000.0, 3000.0, 8000.0)) {
                    coroutineContext.ensureActive()
                    val latSpan = Math.toDegrees(radius / GeoMath.EARTH_RADIUS_M)
                    val lonSpan = latSpan / cos(Math.toRadians(latitude))
                    val lowLat = (latitude - latSpan).coerceAtLeast(-85.05112878)
                    val highLat = (latitude + latSpan).coerceAtMost(85.05112878)
                    val west = normalizedLon - lonSpan; val east = normalizedLon + lonSpan
                    val spans = when {
                        west < -180 -> listOf(west + 360 to 180.0, -180.0 to east)
                        east > 180 -> listOf(west to 180.0, -180.0 to east - 360)
                        else -> listOf(west to east)
                    }
                    var examined = 0
                    for ((lowLon, highLon) in spans) {
                        val sql = "SELECT mcc,mnc,tac,ci,lat,lon,updated FROM cells WHERE grid_lat BETWEEN ? AND ? " +
                            "AND grid_lon BETWEEN ? AND ? AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? LIMIT 4097"
                        val args = arrayOf(floor(lowLat * 100).toInt().toString(), floor(highLat * 100).toInt().toString(),
                            floor(lowLon * 100).toInt().toString(), floor(highLon * 100).toInt().toString(),
                            lowLat.toString(), highLat.toString(), lowLon.toString(), highLon.toString())
                        db.rawQuery(sql, args).use { cursor ->
                            var scanned = 0
                            while (cursor.moveToNext()) {
                                coroutineContext.ensureActive(); examined++; scanned++
                                if (scanned > 4096) { limits += "CANDIDATE_LIMIT"; break }
                                try {
                                    val identity = RemoteCellIdentity(RemoteCellRadio.LTE, cursor.getInt(0), cursor.getInt(1), cursor.getInt(2), cursor.getLong(3))
                                    identity.validate(); require(identity.mcc == index!!.mcc)
                                    val lat = cursor.getDouble(4); val lon = cursor.getDouble(5); val updated = cursor.getLong(6)
                                    require(lat in -85.05112878..85.05112878 && lon in -180.0..180.0 && updated > 0 && updated <= now + 300_000)
                                    if (now - updated > 548L * 86_400_000) { rejected++; continue }
                                    val distance = GeoMath.distance(CoordinateUtils.LatLng(latitude, longitude), CoordinateUtils.LatLng(lat, lon))
                                    if (distance <= radius) points[identity] = OfflineCellCandidate(identity, lat, lon, distance, updated)
                                } catch (_: IllegalArgumentException) { rejected++ }
                            }
                        }
                    }
                    stages.put(JSONObject().put("radius_meters", radius).put("examined", examined).put("valid_candidates", points.size))
                }
            }
            val selected = points.values.sortedBy { it.distanceMeters }.take(256)
            if (points.size > 256) limits += "RECORD_LIMIT"
            val report = JSONObject().put("available", available).put("radio", "LTE").put("stages", stages)
                .put("candidates", selected.size).put("rejected", rejected).put("limits", JSONArray(limits.sorted()))
                .put("online_requests", 0).put("from_cache", false)
                .put("outcome", if (!available) "NOT_AVAILABLE" else if (selected.isNotEmpty()) "SEEDS_FOUND"
                    else if (limits.isNotEmpty()) "INCOMPLETE" else "NO_MATCH_IN_EXPORT")
                .putOpt("nearest_meters", selected.minOfOrNull { it.distanceMeters })
                .put("elapsed_millis", (System.nanoTime() - started) / 1_000_000)
            index?.let { report.put("mcc", it.mcc).put("index_rows", it.rows).put("source_sha256", it.sourceSha256) }
            OfflineCellSearchResult(selected, report).also { cachedKey = key; cachedAt = now; cached = it; lastReport = report }
        } }
}
