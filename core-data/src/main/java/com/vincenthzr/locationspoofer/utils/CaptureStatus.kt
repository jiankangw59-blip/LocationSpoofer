package com.vincenthzr.locationspoofer.utils

/** Status of the first-party scan list, separate from connection state and saved records. */
enum class CaptureStatus { OBSERVED, EMPTY, CACHED, NOT_UPDATED, PERMISSION_DENIED, FAILED }

/** Refresh path only. A broadcast does not identify who initiated the underlying scan. */
enum class RefreshOutcome { NOT_REQUESTED, COMPLETED, NOT_CONFIRMED, LOCAL_COOLDOWN, TIMED_OUT, PERMISSION_DENIED, FAILED }

internal data class RefreshEvent<T>(val outcome: RefreshOutcome, val value: T?)
internal fun wifiRefreshEvent(updated: Boolean) = if (updated) RefreshEvent(RefreshOutcome.COMPLETED, ScanRead.UPDATED)
    else RefreshEvent(RefreshOutcome.NOT_CONFIRMED, ScanRead.NOT_UPDATED)

internal fun cellRefreshError(timeout: Boolean, detail: Throwable?): RefreshOutcome = when {
    timeout -> RefreshOutcome.TIMED_OUT
    detail is SecurityException -> RefreshOutcome.PERMISSION_DENIED
    else -> RefreshOutcome.FAILED
}

/** Per invocation, never shared across collectors. Fallback reads, JSON decoding and saving are not refreshes. */
internal class RefreshProbe {
    var outcome: RefreshOutcome = RefreshOutcome.NOT_REQUESTED
        private set

    fun localCooldown() { outcome = RefreshOutcome.LOCAL_COOLDOWN }

    suspend fun <T> track(attempt: suspend () -> RefreshEvent<T>?): T? {
        val event = try {
            attempt()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: SecurityException) {
            outcome = RefreshOutcome.PERMISSION_DENIED
            throw e
        } catch (e: Exception) {
            outcome = RefreshOutcome.FAILED
            throw e
        }
        // Null is the existing bounded wait expiring; an explicit error callback is an event even without data.
        outcome = event?.outcome ?: RefreshOutcome.TIMED_OUT
        return event?.value
    }
}

internal enum class ScanRead { UPDATED, SNAPSHOT, NOT_UPDATED, DENIED, FAILED }

/** Observed timestamps are mapped from the device's monotonic sample clock, never DB insert time. */
internal data class CaptureWindow(val startUs: Long, val endUs: Long, val endWallMs: Long) {
    fun sampleEpochMs(sampleUs: Long): Long? {
        if (startUs <= 0 || sampleUs !in startUs..endUs) return null
        return (endWallMs - (endUs - sampleUs) / 1_000L).takeIf { it > 0 }
    }
}

internal fun captureStatus(read: ScanRead, returnedRows: Int, observed: Int): CaptureStatus = when {
    read == ScanRead.UPDATED && observed > 0 -> CaptureStatus.OBSERVED
    read == ScanRead.UPDATED && returnedRows == 0 -> CaptureStatus.EMPTY
    read == ScanRead.UPDATED -> CaptureStatus.NOT_UPDATED // Returned rows do not establish when they were observed.
    returnedRows > 0 -> CaptureStatus.CACHED
    read == ScanRead.DENIED -> CaptureStatus.PERMISSION_DENIED
    read == ScanRead.FAILED -> CaptureStatus.FAILED
    else -> CaptureStatus.NOT_UPDATED
}

data class RadioCapture<K>(
    val recordsJson: String, val status: CaptureStatus, val observedAtMs: Map<K, Long> = emptyMap(),
    val cellOperators: Map<K, CellOperatorEvidence> = emptyMap(),
    val refreshOutcome: RefreshOutcome = RefreshOutcome.NOT_REQUESTED
)
