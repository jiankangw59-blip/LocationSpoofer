package com.vincenthzr.locationspoofer.utils

/** Status of the first-party scan list, separate from connection state and saved records. */
enum class CaptureStatus { OBSERVED, EMPTY, CACHED, NOT_UPDATED, PERMISSION_DENIED, FAILED }

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
    val cellOperators: Map<K, CellOperatorEvidence> = emptyMap()
)
