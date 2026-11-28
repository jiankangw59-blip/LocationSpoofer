package com.vincenthzr.locationspoofer.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureStatusTest {
    @Test fun ageUsesElapsedSampleNotTimeOfInsertion() {
        val window = CaptureWindow(1_000_000L, 1_010_000L, 5_000L)
        assertEquals(4_990L, window.sampleEpochMs(1_000_000L))
        assertEquals(5_000L, window.sampleEpochMs(1_010_000L))
        assertNull(window.sampleEpochMs(999_999L))
        assertNull(window.sampleEpochMs(1_010_001L))
        assertNull(window.sampleEpochMs(0L))
    }
    @Test fun onlyAnUpdatedEmptySnapshotIsCompletedEmpty() {
        assertEquals(CaptureStatus.EMPTY, captureStatus(ScanRead.UPDATED, 0, 0))
        for (read in listOf(ScanRead.NOT_UPDATED, ScanRead.SNAPSHOT))
            assertEquals(CaptureStatus.NOT_UPDATED, captureStatus(read, 0, 0))
        assertEquals(CaptureStatus.PERMISSION_DENIED, captureStatus(ScanRead.DENIED, 0, 0))
        assertEquals(CaptureStatus.FAILED, captureStatus(ScanRead.FAILED, 0, 0))
    }
    @Test fun cacheOrFallbackMustNotBecomeObserved() {
        assertEquals(CaptureStatus.CACHED, captureStatus(ScanRead.SNAPSHOT, 1, 0))
        assertEquals(CaptureStatus.CACHED, captureStatus(ScanRead.NOT_UPDATED, 1, 0))
        assertEquals(CaptureStatus.CACHED, captureStatus(ScanRead.UPDATED, 1, 0))
        assertEquals(CaptureStatus.OBSERVED, captureStatus(ScanRead.UPDATED, 2, 1))
    }
    @Test fun bootTimeOrWallClockAnomaliesCannotInventSampleTime() {
        assertNull(CaptureWindow(0, 50, 5_000).sampleEpochMs(30))
        assertNull(CaptureWindow(10_000, 5_000, 5_000).sampleEpochMs(5_000))
        assertNull(CaptureWindow(1_000, 50_000, 1).sampleEpochMs(1_000))
    }
}
