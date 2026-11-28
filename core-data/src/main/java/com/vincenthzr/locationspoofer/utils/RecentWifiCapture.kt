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

    @SuppressLint("MissingPermission")
    suspend fun read(context: Context, manager: WifiManager): List<ScanResult> {
        fun snapshot(): List<ScanResult> {
            // Take the clock reading after the API roundtrip so a scan finishing in the meantime isn't "future".
            val items = try { manager.scanResults.orEmpty() } catch (_: SecurityException) { return emptyList() }
            val nowUs = SystemClock.elapsedRealtimeNanos() / 1000L
            return items.filter { WifiObservationAge.isRecent(it.timestamp, nowUs) }
        }
        val cached = snapshot()
        if (cached.isNotEmpty()) return cached
        val now = SystemClock.elapsedRealtime()
        val prev = lastRequestMs.get()
        if (prev >= 0 && now - prev in 0 until MIN_REQUEST_INTERVAL_MS) return emptyList()
        if (!lastRequestMs.compareAndSet(prev, now)) return emptyList()

        val done = CompletableDeferred<Unit>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) done.complete(Unit)
            }
        }
        val app = context.applicationContext
        var registered = false
        try {
            ContextCompat.registerReceiver(app, receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
            @Suppress("DEPRECATION")
            if (manager.startScan()) withTimeoutOrNull(3_500L) { done.await() }
        } catch (_: SecurityException) {
            // Permissions can be revoked between read and refresh. Never fall back to a stale observation.
        } finally {
            if (registered) app.unregisterReceiver(receiver)
        }
        return snapshot()
    }
}
