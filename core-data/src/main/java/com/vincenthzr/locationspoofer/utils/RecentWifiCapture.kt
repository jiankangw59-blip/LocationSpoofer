package com.vincenthzr.locationspoofer.utils

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/** Called only by the explicit collection flow; no persistent listener, retries, or polling loop. */
internal object RecentWifiCapture {
    private val lastRequestMs = AtomicLong(-1)
    private const val MIN_REQUEST_INTERVAL_MS = 30_000L

    data class Reading(val rows: List<ScanResult>, val status: CaptureStatus, val observedAtMs: Map<String, Long>)

    @SuppressLint("MissingPermission")
    suspend fun read(context: Context, manager: WifiManager): Reading {
        fun snapshot(): List<ScanResult>? = try { manager.scanResults.orEmpty() } catch (_: SecurityException) { null }
        fun recent(rows: List<ScanResult>, nowUs: Long) = rows.filter { WifiObservationAge.isRecent(it.timestamp, nowUs) }
        fun nowUs() = SystemClock.elapsedRealtimeNanos() / 1_000L
        val first = snapshot()
        val cached = recent(first.orEmpty(), nowUs())
        if (cached.isNotEmpty()) return Reading(cached, CaptureStatus.CACHED, emptyMap())
        val now = SystemClock.elapsedRealtime()
        val prev = lastRequestMs.get()
        if ((prev >= 0 && now - prev in 0 until MIN_REQUEST_INTERVAL_MS) || !lastRequestMs.compareAndSet(prev, now))
            return Reading(emptyList(), if (first == null) CaptureStatus.PERMISSION_DENIED else CaptureStatus.NOT_UPDATED, emptyMap())

        val done = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
                    done.complete(intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false))
            }
        }
        val app = context.applicationContext
        var registered = false
        val startUs = nowUs()
        val read = try {
            ContextCompat.registerReceiver(app, receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
            @Suppress("DEPRECATION")
            if (manager.startScan() && withTimeoutOrNull(3_500L) { done.await() } == true) ScanRead.UPDATED
            else ScanRead.NOT_UPDATED
        } catch (_: SecurityException) {
            ScanRead.DENIED
        } finally {
            if (registered) app.unregisterReceiver(receiver)
        }
        val raw = snapshot() ?: return Reading(emptyList(), CaptureStatus.PERMISSION_DENIED, emptyMap())
        val endUs = nowUs()
        val window = CaptureWindow(startUs, endUs, System.currentTimeMillis())
        val rows = recent(raw, endUs)
        val observed = mutableMapOf<String, Long>()
        if (read == ScanRead.UPDATED) for (item in rows) {
            val id = item.BSSID?.takeIf { it.isNotBlank() } ?: continue
            val t = window.sampleEpochMs(item.timestamp) ?: continue
            observed[id] = maxOf(observed[id] ?: t, t)
        }
        return Reading(rows, captureStatus(read, raw.size, observed.size), observed)
    }
}
