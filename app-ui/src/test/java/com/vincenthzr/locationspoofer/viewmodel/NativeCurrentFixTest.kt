package com.vincenthzr.locationspoofer.viewmodel

import org.junit.Assert.*
import org.junit.Test

class NativeCurrentFixTest {
    private val now = 100_000_000_000L
    private fun fix(t: Long, lat: Double = 23.0, lng: Double = 10.0) = NativeCurrentFix(lat, lng, t)

    @Test fun newestValidCacheWinsRegardlessOfProviderOrder() {
        val old = fix(now - 60_000_000_000L)
        val recent = fix(now - 1_000_000_000L)
        val newest = fix(now - 3L)
        assertEquals(newest, newestFreshNativeFix(listOf(old, recent, newest), now))
        assertEquals(newest, newestFreshNativeFix(listOf(newest, recent, old), now))
        assertNull(newestFreshNativeFix(emptyList(), now))
    }

    @Test fun rejectUnknownFutureAndExpiredMonotonicTimestamps() {
        val boundary = fix(now - 30_000_000_000L)
        assertEquals(boundary, newestFreshNativeFix(listOf(boundary), now))
        for (t in listOf(0L, -1L, boundary.elapsedNs - 1L, now + 1L, Long.MAX_VALUE))
            assertNull(newestFreshNativeFix(listOf(fix(t)), now))
    }

    @Test fun rejectInvalidCoordinatesButDoNotRejectEquatorOrDateLine() {
        val valid = fix(now - 1L, 0.0, -180.0)
        for (bad in listOf(fix(now, Double.NaN), fix(now, 90.1), fix(now, 1.0, -181.0), fix(now, 1.0, Double.POSITIVE_INFINITY)))
            assertEquals(valid, newestFreshNativeFix(listOf(bad, valid), now))
    }
}
