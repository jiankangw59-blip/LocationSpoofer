package com.vincenthzr.locationspoofer.viewmodel

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AutoPointHeightRequestsTest {
    @Test fun pickingStartsWithoutAButtonAndStartReusesIt() = runBlocking {
        val gate = AutoPointHeightRequests<Int>(this, 0)
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Int>()
        var calls = 0
        gate.select("A") { calls++; entered.complete(Unit); finish.await() }
        withTimeout(2000) { entered.await() }
        val confirm = async(start = CoroutineStart.UNDISPATCHED) { gate.await("A") { error("duplicate") } }
        finish.complete(38)
        assertEquals(38,confirm.await()); assertEquals(38,gate.await("A") { error("duplicate") })
        assertEquals(1,calls)
    }
    @Test fun onlyFinalSettledPointLoadsAndRetriesAreExplicit() = runBlocking {
        val gate = AutoPointHeightRequests<Int>(this, 1)
        var calls = 0; val done = CompletableDeferred<Unit>()
        gate.select("A") { error("intermediate point was queried") }
        gate.select("B") { error("intermediate point was queried") }
        gate.select("C") { calls++; done.complete(Unit); null }
        withTimeout(2000) { done.await() }
        gate.select("C") { error("automatic retries are not allowed") }
        assertEquals(2,gate.await("C") { ++calls })
        assertEquals(2,calls)
    }
    @Test fun switchingToManualOrAnotherPointInvalidatesAnOutstandingSelection() = runBlocking {
        val gate = AutoPointHeightRequests<Int>(this, 0)
        val started = CompletableDeferred<Unit>(); val stopped = CompletableDeferred<Unit>()
        gate.select("A") {
            started.complete(Unit)
            try { awaitCancellation() } finally { stopped.complete(Unit) }
        }
        withTimeout(2000) { started.await() }
        val old = async(start = CoroutineStart.UNDISPATCHED) { gate.await("A") { error("duplicate") } }
        gate.invalidate()
        withTimeout(2000) { stopped.await() }
        assertNull(old.await())
        assertEquals(7,gate.await("B") { 7 })
    }
}
