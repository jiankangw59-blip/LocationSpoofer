package com.vincenthzr.locationspoofer.xposed.utils

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test

class EventLogLimiterTest {
    @Test fun independentEventsResumeAtTheirOwnMonotonicDeadline() {
        var now = 0L
        val limiter = EventLogLimiter({ now })
        assertTrue(limiter.allow("scan", 5000)); assertFalse(limiter.allow("scan", 5000))
        assertTrue(limiter.allow("reload", 5000))
        now = 4999; assertFalse(limiter.allow("scan", 5000))
        now = 5000; assertTrue(limiter.allow("scan", 5000))
    }
    @Test fun dynamicKeysEvictTheLeastRecentlyUsedEntry() {
        val limiter = EventLogLimiter({ 100L }, 2)
        assertTrue(limiter.allow("a", 5000)); assertTrue(limiter.allow("b", 5000))
        assertFalse(limiter.allow("a", 5000)); assertTrue(limiter.allow("c", 5000))
        assertFalse(limiter.allow("a", 5000)); assertTrue(limiter.allow("b", 5000))
    }
    @Test fun racingCallersEmitOnceForOneEventAndDeadline() {
        val limiter = EventLogLimiter({ 100L })
        val workers = Executors.newFixedThreadPool(8)
        try {
            val results = workers.invokeAll((1..64).map { Callable { limiter.allow("same", 5000) } })
            assertEquals(1, results.count { it.get() })
        } finally { workers.shutdownNow() }
    }
}
