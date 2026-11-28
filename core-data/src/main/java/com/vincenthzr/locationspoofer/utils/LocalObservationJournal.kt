package com.vincenthzr.locationspoofer.utils

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

enum class ObservationKind { WIFI, CELL, BLE }
data class LocalObservationMark(val inLatestRound: Boolean, val lastSeenAtMs: Long)

/** Optional UI evidence. Times are monotonic sample timestamps projected onto the local wall clock,
 * not persistence times and not independent hardware-supplied UTC timestamps. */
class LocalObservationSnapshot internal constructor(
    val hasRound: Boolean, private val find: (ObservationKind, String) -> LocalObservationMark?
) {
    fun lookup(kind: ObservationKind, rawId: String) = find(kind, rawId)
    companion object { val EMPTY = LocalObservationSnapshot(false) { _, _ -> null } }
}

internal class ObservationKeys(secret: ByteArray, private val point: Long) {
    private val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }
    @Synchronized fun key(kind: ObservationKind, raw: String): String? {
        if (raw.length !in 1..180) return null
        val id = raw.trim().uppercase(Locale.ROOT)
        if (id.isEmpty()) return null
        val canonical = if (kind == ObservationKind.CELL) id else {
            if (!ADDRESS.matches(id)) return null
            val compact = id.replace(":", "").replace("-", "")
            if (compact in PLACEHOLDERS) return null
            compact
        }
        return androidSafeHex(mac.doFinal(("$point/${kind.name}/$canonical").toByteArray(Charsets.UTF_8)), 16)
    }
    private fun androidSafeHex(bytes: ByteArray, n: Int): String = buildString(2*n) {
        for (i in 0 until n) {
            val b = bytes[i].toInt() and 255
            append(HEX[b ushr 4]); append(HEX[b and 15])
        }
    }
    private companion object {
        const val HEX = "0123456789abcdef"
        val ADDRESS = Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}|([0-9A-F]{2}-){5}[0-9A-F]{2}|[0-9A-F]{12}")
        val PLACEHOLDERS = setOf("000000000000", "FFFFFFFFFFFF", "020000000000")
    }
}

internal data class SeenReceipt(val round: Long, val time: Long)
internal data class JournalPoint(val round: Long, val revision: Long, val entries: Map<String, SeenReceipt>) {
    fun advance(ticket: Long, version: Long, changes: Map<String, Long>): JournalPoint {
        if (ticket <= round || version <= 0) return this
        // Tickets order rounds across wall-clock changes, restarts and delayed callbacks.
        val next = LinkedHashMap<String, SeenReceipt>()
        for ((id, time) in changes) if (time > 0 && next.size < MAX_ENTRIES) next[id] = SeenReceipt(ticket, time)
        for ((id, value) in entries) {
            if (next.size >= MAX_ENTRIES) break
            next.putIfAbsent(id, value)
        }
        return JournalPoint(ticket, version, next)
    }
    fun snapshot(version: Long, keys: ObservationKeys): LocalObservationSnapshot {
        if (round <= 0 || revision != version) return LocalObservationSnapshot.EMPTY
        return LocalObservationSnapshot(true) { kind, id ->
            keys.key(kind, id)?.let(entries::get)?.let { LocalObservationMark(it.round == round, it.time) }
        }
    }
    fun encoded(): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(entries.size)
            for ((id, value) in entries) { out.writeUTF(id); out.writeLong(value.round); out.writeLong(value.time) }
        }
        bytes.toByteArray()
    }
    companion object {
        val EMPTY = JournalPoint(0, 0, emptyMap())
        const val MAX_ENTRIES = 512
        fun decode(round: Long, revision: Long, blob: ByteArray): JournalPoint {
            if (blob.size > 4 + MAX_ENTRIES * 50) throw IOException("oversized receipt")
            return DataInputStream(ByteArrayInputStream(blob)).use { input ->
                val size = input.readInt()
                if (size !in 0..MAX_ENTRIES) throw IOException("invalid receipt count")
                val entries = LinkedHashMap<String, SeenReceipt>()
                repeat(size) {
                    val id = input.readUTF()
                    val r = input.readLong(); val t = input.readLong()
                    if (id.length != 32 || r !in 1..round || t <= 0 || entries.put(id, SeenReceipt(r, t)) != null)
                        throw IOException("invalid receipt")
                }
                if (input.read() != -1) throw IOException("trailing receipt bytes")
                JournalPoint(round, revision, entries)
            }
        }
    }
}

/** Sidecar for first-party collection displays ONLY. Never used for selecting/loading environment output.
 * All I/O methods belong on a worker thread. Coordinates and raw radio identifiers never enter this DB.
 */
class LocalObservationJournal private constructor(context: Context, private val file: File) {
    // A re-created/restored Room file can reuse its old numeric point IDs. Do not join across files.
    // The test package intentionally has no environment DB of its own.
    private val environmentFile = context.getDatabasePath("environment_database").absolutePath
    private fun storeIdentity(): String = try {
        android.system.Os.stat(environmentFile).let { "${it.st_dev}:${it.st_ino}" }
    } catch (e: android.system.ErrnoException) {
        if (e.errno == android.system.OsConstants.ENOENT) "absent" else throw IOException("database identity unavailable", e)
    }
    private val changes = MutableStateFlow<Pair<Long, Long>?>(null)
    val changedPoint: StateFlow<Pair<Long, Long>?> get() = changes
    private var invalidation = 0L
    private fun invalidateAll() { changes.value = 0L to ++invalidation }
    // If a write fails, prefer no evidence to showing a stale "last saved" result in this process.
    @Volatile private var failedSinceOpen = false
    private val store = object : SQLiteOpenHelper(context.applicationContext ?: context, file.path, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE meta(id INTEGER PRIMARY KEY,seq INTEGER NOT NULL,floor INTEGER NOT NULL,secret BLOB NOT NULL)")
            db.execSQL("INSERT INTO meta VALUES (1,0,0,?)", arrayOf(SecureRandom().generateSeed(32)))
            db.execSQL("CREATE TABLE point(id INTEGER PRIMARY KEY,r INTEGER NOT NULL,v INTEGER NOT NULL,seen BLOB NOT NULL,origin TEXT NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) { throw SQLiteException("unsupported journal version") }
    }
    private fun <T> transaction(body: (SQLiteDatabase) -> T): T = store.writableDatabase.let { db ->
        db.beginTransaction()
        try { body(db).also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
    }
    private fun SQLiteDatabase.long(query: String) = compileStatement(query).use { it.simpleQueryForLong() }

    @Synchronized fun beginRound(): Long = try {
        transaction { db ->
            db.execSQL("UPDATE meta SET seq=seq+1 WHERE id=1")
            db.long("SELECT seq FROM meta WHERE id=1")
        }
    } catch (_: SQLiteException) { writeFailed(); 0L }

    @Synchronized fun record(locationId: Long, round: Long, seen: Map<ObservationKind, Map<String, Long>>, pointTimestampMs: Long) {
        if (locationId <= 0 || round <= 0 || pointTimestampMs <= 0) return
        try {
            val accepted = transaction { db ->
                val permitted = db.rawQuery("SELECT 1 FROM meta WHERE id=1 AND CAST(? AS INTEGER) BETWEEN floor+1 AND seq", arrayOf(round.toString()))
                    .use { it.moveToFirst() }
                if (!permitted) return@transaction false
                val origin = storeIdentity()
                val previous = db.rawQuery("SELECT r,v,seen FROM point WHERE id=? AND origin=?", arrayOf(locationId.toString(), origin)).use {
                    if (it.moveToFirst()) JournalPoint.decode(it.getLong(0), it.getLong(1), it.getBlob(2)) else JournalPoint.EMPTY
                }
                if (round <= previous.round) return@transaction false
                val keys = db.rawQuery("SELECT secret FROM meta WHERE id=1", null).use { it.moveToFirst(); ObservationKeys(it.getBlob(0), locationId) }
                val received = LinkedHashMap<String, Long>()
                for ((kind, ids) in seen) for ((id, time) in ids) {
                    if (received.size >= JournalPoint.MAX_ENTRIES) break
                    if (time > 0) keys.key(kind, id)?.let { received[it] = time }
                }
                val point = previous.advance(round, pointTimestampMs, received)
                val row = ContentValues(5).apply { put("id", locationId); put("r", round); put("v", pointTimestampMs); put("seen", point.encoded()); put("origin", origin) }
                db.insertWithOnConflict("point", null, row, SQLiteDatabase.CONFLICT_REPLACE).also {
                    if (it < 0) throw SQLiteException("journal insert failed")
                }
                db.execSQL("DELETE FROM point WHERE id IN (SELECT id FROM point ORDER BY r DESC LIMIT -1 OFFSET 64)")
                true
            }
            if (accepted) changes.value = locationId to round
        } catch (_: SQLiteException) { writeFailed() }
          catch (_: IOException) { writeFailed() }
    }
    private fun writeFailed() { failedSinceOpen = true; invalidateAll() }

    /** A confirmed primary deletion invalidates any in-flight ticket; a late callback cannot reinsert a deleted point. */
    fun forget(locationIds: List<Long>) = remove(locationIds)
    fun forgetAll() = remove(null)

    @Synchronized private fun remove(locationIds: List<Long>?) {
        if (locationIds?.isEmpty() == true || !file.isFile) return
        try {
            transaction { db ->
                if (locationIds == null) db.delete("point", null, null)
                else for (id in locationIds) db.delete("point", "id=?", arrayOf(id.toString()))
                db.execSQL("UPDATE meta SET floor=seq WHERE id=1")
            }
            invalidateAll()
        } catch (_: SQLiteException) { writeFailed() }
    }

    @Synchronized fun snapshot(locationId: Long, pointTimestampMs: Long): LocalObservationSnapshot {
        if (locationId <= 0 || pointTimestampMs <= 0 || failedSinceOpen || !file.isFile) return LocalObservationSnapshot.EMPTY
        return try {
            store.readableDatabase.rawQuery("SELECT r,v,seen,secret FROM point,meta WHERE point.id=? AND meta.id=1 AND point.v=? AND origin=?",
                arrayOf(locationId.toString(), pointTimestampMs.toString(), storeIdentity())).use {
                if (!it.moveToFirst()) LocalObservationSnapshot.EMPTY else
                    JournalPoint.decode(it.getLong(0), it.getLong(1), it.getBlob(2)).snapshot(pointTimestampMs, ObservationKeys(it.getBlob(3), locationId))
            }
        } catch (_: SQLiteException) { LocalObservationSnapshot.EMPTY } catch (_: IOException) { LocalObservationSnapshot.EMPTY }
    }
    fun close() = synchronized(instances) {
        if (instances[file.path] === this) {
            synchronized(this) { store.close() }; instances.remove(file.path)
        }
        Unit
    }
    companion object {
        private val instances = mutableMapOf<String, LocalObservationJournal>()
        fun get(context: Context): LocalObservationJournal {
            val file = File(context.noBackupFilesDir, "local_observation_history_v1.db").absoluteFile
            return synchronized(instances) { instances.getOrPut(file.path) { LocalObservationJournal(context, file) } }
        }
    }
}
