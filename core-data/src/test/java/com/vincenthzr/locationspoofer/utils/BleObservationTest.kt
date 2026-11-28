package com.vincenthzr.locationspoofer.utils

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BleObservationTest {
    @Test fun emptyCompletionDiffersFromPlatformFailureAndOffOrDenied() = runBlocking {
        val empty = collectBleWindow<Int>(1, start = { _, _ -> }, stop = {})
        assertEquals(emptyList<Int>(), empty)
        assertEquals(BleScanStatus.EMPTY, BleObservation.completed("[]", 0).status)
        val failed = collectBleWindow<Int>(10000, start = { receive, fail -> receive(1); fail(); receive(2) }, stop = {})
        assertNull(failed)
        for (state in listOf(BleScanStatus.FAILED, BleScanStatus.ADAPTER_OFF, BleScanStatus.PERMISSION_DENIED, BleScanStatus.UNAVAILABLE)) {
            val result = BleObservation(state)
            assertNotEquals(BleScanStatus.EMPTY, result.status)
            assertEquals(0, result.count); assertEquals("[]", result.recordsJson)
        }
    }

    @Test fun stopAlwaysRunsAndLateCallbacksDoNotChangeCompletedWindow() = runBlocking {
        lateinit var late: (Int) -> Unit
        var stops = 0
        val received = collectBleWindow<Int>(1, start = { receive, _ -> late = receive; receive(1) }, stop = { stops++ })
        late(2)
        assertEquals(listOf(1), received)
        assertEquals(BleScanStatus.OBSERVED, BleObservation.completed("[{}]", 1).status)
        assertEquals(1, stops)
    }

    @Test fun cancellationCleansUpAndIsNotReportedAsAZeroObservation() = runBlocking {
        var stopped = 0
        val task = async(start = CoroutineStart.UNDISPATCHED) {
            collectBleWindow<Int>(10000, start = { _, _ -> }, stop = { stopped++ })
        }
        task.cancelAndJoin()
        assertTrue(task.isCancelled)
        assertEquals(1, stopped)
    }
}
