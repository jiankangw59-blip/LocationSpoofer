package com.vincenthzr.locationspoofer.utils

import org.junit.Assert.*
import org.junit.Test

class RadioObservationTest {
    @Test fun cachedReadsShareObservationTimeAndNeverReportFutureSamples() {
        for (now in listOf(0L,1L,7_123_456_789L,9_000_000_000_000L)) {
            val frame=RadioObservation.timestamp(now,RadioObservation.WIFI_PERIOD_NS)
            assertTrue(frame<=now && frame>=0)
            assertEquals(frame,RadioObservation.timestamp(frame+1,RadioObservation.WIFI_PERIOD_NS))
        }
    }
    @Test fun longUptimeAndFrameBoundariesRemainMonotonic() {
        val frames=(0..50).map { RadioObservation.timestamp(900_000_000_000L+it*700_000_000L,RadioObservation.CELL_PERIOD_NS) }
        assertTrue(frames.zipWithNext().all { (a,b)-> b>=a })
        assertTrue(frames.distinct().size>3)
    }
    @Test fun signalIsBoundedRepeatableAndTimeBased() {
        val values=(0..30).map { RadioObservation.dbm(-90,"fixed-cell",it*2_000_000_000L,true) }
        assertTrue(values.all { it in -92..-88 });assertTrue(values.distinct().size>1)
        assertTrue(values.zipWithNext().all { (a,b)->kotlin.math.abs(b-a)<=2 })
        assertEquals(RadioObservation.dbm(-90,"fixed-cell",0,true),RadioObservation.dbm(-90,"fixed-cell",0,true))
        assertEquals(-90,RadioObservation.dbm(-90,"fixed-cell",0,false))
    }
}
