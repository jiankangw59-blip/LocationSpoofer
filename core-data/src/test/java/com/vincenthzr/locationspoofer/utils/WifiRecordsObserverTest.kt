package com.vincenthzr.locationspoofer.utils

import com.vincenthzr.locationspoofer.data.repository.notifyWifiRecordsFound
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WifiRecordsObserverTest {
    @Test
    fun observerFailuresDoNotInterruptTheLookup() = runBlocking {
        val counts = mutableListOf<Int>()
        notifyWifiRecordsFound({ counts += it; throw IllegalStateException("fixture observer") }, 4)
        notifyWifiRecordsFound({ counts += it; throw CancellationException("fixture observer") }, 7)
        assertEquals(listOf(4, 7), counts)
    }

    @Test
    fun cancellingTheQueryInTheObserverStopsTheLookup() = runBlocking {
        var reachedNextStage = false
        val pending = launch {
            val lookupContext = currentCoroutineContext()
            notifyWifiRecordsFound({
                lookupContext.cancel()
                throw IllegalStateException("fixture observer")
            }, 4)
            reachedNextStage = true
        }
        pending.join()
        assertFalse(reachedNextStage)
    }
}
