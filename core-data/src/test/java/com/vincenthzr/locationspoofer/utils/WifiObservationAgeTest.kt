package com.vincenthzr.locationspoofer.utils

import org.junit.Assert.*
import org.junit.Test

class WifiObservationAgeTest {
    @Test fun cutoffIsInclusiveAndUsesMicrosecondsNotMillis() {
        val now = 60_000_000L
        assertTrue(WifiObservationAge.isRecent(now,now))
        assertTrue(WifiObservationAge.isRecent(now-10_000_000L,now))
        assertFalse(WifiObservationAge.isRecent(now-10_000_001L,now))
        assertFalse(WifiObservationAge.isRecent(60_000L,now))
    }
    @Test fun missingFutureAndOtherBootValuesAreNotMeasurements() {
        assertFalse(WifiObservationAge.isRecent(0,10))
        assertFalse(WifiObservationAge.isRecent(-1,10))
        assertFalse(WifiObservationAge.isRecent(12,11))
        assertFalse(WifiObservationAge.isRecent(1,-1))
        assertFalse(WifiObservationAge.isRecent(Long.MAX_VALUE,1))
    }
}
