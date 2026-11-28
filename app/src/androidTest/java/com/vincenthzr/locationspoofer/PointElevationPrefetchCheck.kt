package com.vincenthzr.locationspoofer

import android.app.Instrumentation
import android.content.Context
import android.os.Build
import android.os.Bundle
import com.vincenthzr.locationspoofer.data.elevation.PointHeightCache
import com.vincenthzr.locationspoofer.data.elevation.TargetElevation
import com.vincenthzr.locationspoofer.data.elevation.TargetElevationSource
import com.vincenthzr.locationspoofer.viewmodel.AutoPointHeightRequests
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.file.Files
import kotlin.math.abs

// Exercises the production prefetch coordinator's selection event, not UI automation or a simulation start.
// No live location, user preferences, user databases, or production surface cache are accessed.
internal fun Instrumentation.checkPointElevationPrefetch(): Bundle {
    val out = Bundle()
    if (Build.VERSION.SDK_INT < 34) return out.apply { putString("result", "UNAVAILABLE_PLATFORM") }
    val ctx = targetContext
    var dir: File? = null
    val result = try {
        val fixture = Files.createTempDirectory(ctx.cacheDir.toPath(), "point-prefetch-fixture-").toFile()
        dir = fixture
        runBlocking {
            // A single calling thread (the instrumentation event loop), just like the production Main scope.
            val managed = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
            try { exercisePubPoints(ctx, fixture, managed, out) }
            finally { managed.cancel() }
        }
    } catch (e: Exception) { "FAIL_" + e.javaClass.simpleName }
    finally {
        // Only direct, newly created files inside the fixture; do not traverse the target app's cache.
        val files = dir?.listFiles()
        val clean = dir == null || (files != null && files.all { it.isFile && it.delete() } && dir.delete())
        out.putBoolean("temporary_cache_removed", clean)
    }
    out.putString("result", if (out.getBoolean("temporary_cache_removed")) result else "FAIL_CLEANUP")
    return out
}

private data class PrefetchFixture(val key: String, val lat: Double, val lon: Double, val range: ClosedFloatingPointRange<Double>)

private suspend fun exercisePubPoints(ctx: Context, dir: File, scope: CoroutineScope, out: Bundle): String {
    val points = listOf(Triple("berlin", 52.52 to 13.41, 20.0..75.0), Triple("dead_sea", 31.5 to 35.5, -480.0..-320.0))
    val cache = PointHeightCache(dir)
    val requests = AutoPointHeightRequests<TargetElevation>(scope)
    var onlineCalls = 0
    val saved = mutableMapOf<String, TargetElevation>()
    val samples = points.map { (_, coords, range) ->
        PrefetchFixture(requireNotNull(TargetElevationSource.key(coords.first, coords.second)), coords.first, coords.second, range)
    }
    try {
        // A deselected cell must not start its deferred task when two selection events arrive back to back.
        var obsoleteCalls = 0
        requests.select("0.0000,0.0000") { obsoleteCalls++; null }
        for ((index, point) in samples.withIndex()) {
            val name = points[index].first
            val receipt = CompletableDeferred<Pair<Boolean, TargetElevation?>>()
            requests.select(point.key) {
                val hit = withContext(Dispatchers.IO) { cache.get(point.key) }
                val value = hit ?: run { onlineCalls++; TargetElevationSource.lookup(ctx, point.lat, point.lon) }
                if (hit == null && value != null) withContext(Dispatchers.IO) { cache.put(point.key, value) }
                receipt.complete((hit != null) to value)
                value
            }
            // Observe the already issued selection; never call requests.await, preview, or start.
            val (hit, value) = withTimeoutOrNull(12_000L) { receipt.await() } ?: return "FAIL_NO_SELECTION_CALLBACK_" + name
            if (value == null) return "UNAVAILABLE_SOURCE_OR_MODEL_" + name
            if (hit || value.surfaceMslMeters !in point.range || abs(value.ellipsoidMeters - value.surfaceMslMeters) < 2.0)
                return "FAIL_TARGET_OR_DATUM_" + name
            saved[point.key] = value
            out.putDouble(name + "_automatic_msl_m", value.surfaceMslMeters)
            out.putDouble(name + "_automatic_ellipsoid_m", value.ellipsoidMeters)
        }
        out.putInt("online_calls", onlineCalls)
        out.putInt("obsolete_loads", obsoleteCalls)
        if (onlineCalls != 2 || obsoleteCalls != 0) return "FAIL_SELECTION_CALL_COUNT"
    } finally { requests.invalidate() }

    // New coordinator AND cache reader; networking is not available in this loader.
    val offline = AutoPointHeightRequests<TargetElevation>(scope)
    val reopened = PointHeightCache(dir)
    val missing = requireNotNull(TargetElevationSource.key(-33.86, 151.21))
    val revisit = samples.map { it.key } + missing
    var offlineHits = 0
    try {
        for (key in revisit) {
            val receipt = CompletableDeferred<TargetElevation?>()
            offline.select(key) {
                val v = withContext(Dispatchers.IO) { reopened.get(key) }
                receipt.complete(v)
                v
            }
            // Box the nullable value: a real cache miss is distinct from no selection callback.
            val boxed = withTimeoutOrNull(4_000L) { listOf(receipt.await()) } ?: return "FAIL_NO_OFFLINE_CALLBACK"
            if (boxed.single() != saved[key]) return "FAIL_CACHE_IDENTITY"
            if (boxed.single() != null) offlineHits++
        }
    } finally { offline.invalidate() }
    out.putInt("offline_cache_hits", offlineHits)
    out.putBoolean("offline_unseen_miss", true)
    return if (offlineHits == 2) "PASS" else "FAIL_CACHE_COUNT"
}
