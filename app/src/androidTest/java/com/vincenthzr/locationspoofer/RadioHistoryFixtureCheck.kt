package com.vincenthzr.locationspoofer

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import com.vincenthzr.locationspoofer.utils.LocalObservationJournal
import com.vincenthzr.locationspoofer.utils.ObservationKind
import java.io.File
import java.nio.file.Files

/** Offline, isolated from the production journal. No radio, location, HTTP, or simulation. */
internal fun checkRadioHistoryFixtures(app: Context): Bundle {
    val result = Bundle()
    var checks = 0
    fun verify(label: String, value: Boolean) {
        check(value) { label }
        checks++
    }
    var sandbox: File? = null
    var journal: LocalObservationJournal? = null
    try {
        val dir = Files.createTempDirectory(app.cacheDir.toPath(), "radio-history-fixture-").toFile()
        sandbox = dir
        val ctx = object : ContextWrapper(app) {
            override fun getNoBackupFilesDir(): File = dir
            override fun getDatabasePath(name: String): File = File(dir, name)
        }
        val wifi = ObservationKind.WIFI
        val a = "02:00:00:00:00:0A"
        val b = "02:00:00:00:00:0B"
        val db = LocalObservationJournal.get(ctx)
        journal = db
        val t = 1_700_000_000_000L
        val one = db.beginRound()
        db.record(locationId = 7L, round = one, seen = mapOf(wifi to mapOf(a to t)), pointTimestampMs = t + 10L)
        val s1 = db.snapshot(7L, t + 10L)
        verify("initial A", s1.lookup(wifi, a)?.inLatestRound == true)
        verify("absent B", s1.lookup(wifi, b) == null)
        val two = db.beginRound()
        db.record(locationId = 7L, round = two, seen = mapOf(wifi to mapOf(b to t + 100L)), pointTimestampMs = t + 110L)
        val s2 = db.snapshot(7L, t + 110L)
        verify("A is historical", s2.lookup(wifi, a)?.inLatestRound == false)
        verify("A time unchanged", s2.lookup(wifi, a)?.lastSeenAtMs == t)
        verify("B latest", s2.lookup(wifi, b)?.inLatestRound == true)
        verify("wrong parent revision", db.snapshot(7L, t + 999L).lookup(wifi, a) == null)
        verify("another point", db.snapshot(8L, t + 110L).lookup(wifi, b) == null)
        verify("another radio", s2.lookup(ObservationKind.BLE, b) == null)
        db.close()
        journal = null
        val reopened = LocalObservationJournal.get(ctx)
        journal = reopened
        verify("durable history", reopened.snapshot(7L, t + 110L).lookup(wifi, a)?.lastSeenAtMs == t)
        val empty = reopened.beginRound()
        reopened.record(locationId = 7L, round = empty, seen = emptyMap(), pointTimestampMs = t + 200L)
        val s3 = reopened.snapshot(7L, t + 200L)
        verify("empty is not fresh B", s3.lookup(wifi, b)?.inLatestRound == false)
        verify("empty preserves B time", s3.lookup(wifi, b)?.lastSeenAtMs == t + 100L)
        reopened.record(7L, two, mapOf(wifi to mapOf(a to t + 400L)), t + 500L)
        verify("late save did not replace latest point revision", reopened.snapshot(7L, t + 200L).hasRound)
        val pending = reopened.beginRound()
        reopened.forget(listOf(7L))
        verify("deleted point has no receipt", !reopened.snapshot(7L, t + 200L).hasRound)
        reopened.record(7L, pending, mapOf(wifi to mapOf(a to t + 600L)), t + 610L)
        verify("delete cannot be undone by an older request", !reopened.snapshot(7L, t + 610L).hasRound)
        result.putString("result", "PASS")
    } catch (e: Exception) {
        result.putString("result", "FAIL")
        result.putString("failure", e.message?.take(160) ?: e.javaClass.simpleName)
    } finally {
        try { journal?.close() } finally {
            if (sandbox?.deleteRecursively() == false) result.putString("result", "FAIL")
        }
        result.putInt("assertions", checks)
    }
    return result
}