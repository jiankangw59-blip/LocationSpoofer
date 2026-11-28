package com.vincenthzr.locationspoofer.utils

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

enum class BleScanStatus { OBSERVED, EMPTY, ADAPTER_OFF, PERMISSION_DENIED, UNAVAILABLE, FAILED }

data class BleObservation(val status: BleScanStatus, val recordsJson: String = "[]", val count: Int = 0) {
    companion object {
        fun completed(json: String, count: Int) =
            BleObservation(if (count == 0) BleScanStatus.EMPTY else BleScanStatus.OBSERVED, json, count)
    }
}

/** null means the platform reported failure, unlike a completed window with zero reports. */
internal suspend fun <T> collectBleWindow(
    durationMs: Long, start: (offer: (T) -> Unit, fail: () -> Unit) -> Unit, stop: () -> Unit
): List<T>? {
    val reports = ArrayList<T>()
    val failed = CompletableDeferred<Unit>()
    var closed = false
    try {
        start({ report -> synchronized(reports) { if (!closed && !failed.isCompleted) reports.add(report) } },
              { synchronized(reports) { if (!closed) failed.complete(Unit) }; Unit })
        withTimeoutOrNull(durationMs) { failed.await() }
        return synchronized(reports) {
            closed = true
            if (failed.isCompleted) null else reports.toList()
        }
    } finally {
        synchronized(reports) { closed = true }
        try { stop() } catch (_: SecurityException) {
            // The permission can be revoked during the scan.
        } catch (_: IllegalStateException) {
            // The adapter can be turned off during the scan.
        }
    }
}
