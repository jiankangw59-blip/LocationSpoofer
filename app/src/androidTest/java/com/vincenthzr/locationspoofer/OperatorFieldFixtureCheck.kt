package com.vincenthzr.locationspoofer

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import com.vincenthzr.locationspoofer.utils.CellOperatorEvidence
import com.vincenthzr.locationspoofer.utils.LocalObservationJournal
import com.vincenthzr.locationspoofer.utils.ObservationKind
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Create the independently specified .33 schema, with one timestamped cell but no operator column. */
private fun seedLegacyFieldFixture(dir: File, cell: String, t: Long) {
    val secret = ByteArray(32) { it.toByte() }
    val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }
    val token = mac.doFinal(("7/CELL/" + cell).toByteArray(Charsets.UTF_8)).take(16)
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
    val old = ByteArrayOutputStream().also { stream -> DataOutputStream(stream).use {
        it.writeInt(1); it.writeUTF(token); it.writeLong(1); it.writeLong(t)
    } }.toByteArray()
    SQLiteDatabase.openOrCreateDatabase(File(dir, "local_observation_history_v1.db"), null).use { db ->
        db.execSQL("CREATE TABLE meta(id INTEGER PRIMARY KEY,seq INTEGER NOT NULL,floor INTEGER NOT NULL,secret BLOB NOT NULL)")
        db.execSQL("CREATE TABLE point(id INTEGER PRIMARY KEY,r INTEGER NOT NULL,v INTEGER NOT NULL,seen BLOB NOT NULL,origin TEXT NOT NULL)")
        db.execSQL("INSERT INTO meta VALUES (1,1,0,?)", arrayOf(secret))
        db.execSQL("INSERT INTO point VALUES (7,1,?,?, 'absent')", arrayOf(t, old))
        db.version = 1
    }
}

/** Isolated temporary DB only. No access to the real radio, location, or application data. */
internal fun checkOperatorFieldFixtures(app: Context): Bundle {
    val out = Bundle()
    var count = 0
    var folder: File? = null
    var journal: LocalObservationJournal? = null
    fun verify(name: String, passed: Boolean) { check(passed) { name }; count++ }
    try {
        val dir = Files.createTempDirectory(app.cacheDir.toPath(), "operator-field-fixture-").toFile()
        folder = dir
        val ctx = object : ContextWrapper(app) {
            override fun getNoBackupFilesDir() = dir
            override fun getDatabasePath(name: String) = File(dir, name)
        }
        val t = 1_700_000_000_000L
        val k = ObservationKind.CELL
        val a = "LTE_FIXTURE_A"; val b = "LTE_FIXTURE_B"; val c = "LTE_FIXTURE_C"
        seedLegacyFieldFixture(dir, a, t)
        val db = LocalObservationJournal.get(ctx); journal = db
        fun mark(id: String, stamp: Long) = db.snapshot(7L, stamp).lookup(k, id)
        verify("33 timestamp preserved", mark(a, t)?.lastSeenAtMs == t)
        verify("33 fields unverified", mark(a, t)?.cellOperator == null)
        val two = CellOperatorEvidence.fromReported("460", "00")
        val three = CellOperatorEvidence.fromReported("460", "000")
        val partial = CellOperatorEvidence.fromReported(null, "02")
        val missing = CellOperatorEvidence.fromReported(null, null)
        db.record(7L, db.beginRound(), mapOf(k to mapOf(b to t+10, c to t+11)), t+12,
            cellOperators=mapOf(b to two, c to partial, a to three))
        verify("A remains historical", mark(a, t+12)?.inLatestRound == false)
        verify("A cannot gain fields without being seen", mark(a, t+12)?.cellOperator == null)
        verify("two zeros preserved", mark(b, t+12)?.cellOperator?.mnc == "00")
        verify("MCC preserved", mark(b, t+12)?.cellOperator?.mcc == "460")
        verify("one missing field", mark(c, t+12)?.cellOperator == partial)
        db.close(); journal = null
        val reopened = LocalObservationJournal.get(ctx); journal = reopened
        fun field(stamp: Long) = reopened.snapshot(7, stamp).lookup(k, b)?.cellOperator
        verify("field survives reopen", field(t+12) == two)
        reopened.record(7L, reopened.beginRound(), mapOf(k to mapOf(b to t+20)), t+21, cellOperators=mapOf(b to three))
        verify("three zeros preserved", field(t+21)?.mnc == "000")
        reopened.record(7L, reopened.beginRound(), mapOf(k to mapOf(b to t+30)), t+31, cellOperators=mapOf(b to missing))
        verify("both missing differs from unverified", field(t+31) == missing)
        verify("previous code not retained", field(t+31)?.mnc == null)
        reopened.record(7L, reopened.beginRound(), mapOf(k to mapOf(b to t+40)), t+41)
        verify("unverified new observation drops old field evidence", field(t+41) == null)
        out.putString("result","PASS")
    } catch (e: Exception) {
        out.putString("result","FAIL"); out.putString("failure",e.message?.take(140) ?: e.javaClass.simpleName)
    } finally {
        try { journal?.close() } finally {
            if (folder?.deleteRecursively() == false) out.putString("result","FAIL")
        }
        out.putInt("assertions",count)
    }
    return out
}