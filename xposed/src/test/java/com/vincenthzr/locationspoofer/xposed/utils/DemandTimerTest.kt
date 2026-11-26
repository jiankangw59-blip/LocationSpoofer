package com.vincenthzr.locationspoofer.xposed.utils

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DemandTimerTest {
    private class Session(val callback: () -> Unit) : AutoCloseable {
        var cancelled = false
        override fun close() { cancelled = true }
    }

    @Test fun noDemandSchedulesNothingAndRetiredCallbacksCannotRun() {
        val sessions = mutableListOf<Session>()
        val changes = mutableListOf<Boolean>()
        var outputs = 0
        var ticks = 0
        val gate = DemandTimer({ callback -> Session(callback).also { sessions += it } },
            { changes += it }, { ticks++ }) { outputs++ }
        repeat(100) { gate.setRequired(false) }
        assertTrue(sessions.isEmpty())
        gate.setRequired(true)
        gate.setRequired(true)
        assertEquals(1, sessions.size)
        sessions[0].callback()
        assertEquals(1, outputs)
        gate.setRequired(false)
        assertTrue(sessions[0].cancelled)
        sessions[0].callback()
        assertEquals(1, outputs)
        gate.setRequired(true)
        assertEquals(2, sessions.size)
        sessions[0].callback()
        sessions[1].callback()
        assertEquals(2, outputs)
        gate.close()
        assertTrue(sessions[1].cancelled)
        sessions[1].callback()
        gate.setRequired(true)
        assertEquals(2, outputs)
        assertEquals(2, ticks)
        assertEquals(listOf(true, false, true, false), changes)
    }

    @Test fun simultaneousSubscriptionEventsCreateOneOutputSession() {
        val sessions = mutableListOf<Session>()
        val gate = DemandTimer({ callback -> Session(callback).also { sessions += it } }) {}
        val start = CountDownLatch(1)
        val done = CountDownLatch(16)
        val executor = Executors.newFixedThreadPool(4)
        try {
            repeat(16) { executor.execute { start.await(); gate.setRequired(true); done.countDown() } }
            start.countDown()
            assertTrue(done.await(3, TimeUnit.SECONDS))
            assertEquals(1, sessions.size)
        } finally { gate.close(); executor.shutdownNow() }
    }

    @Test fun lastClientMayCancelDuringItsOwnFinalOutput() {
        val sessions = mutableListOf<Session>()
        lateinit var gate: DemandTimer
        gate = DemandTimer({ callback -> Session(callback).also { sessions += it } }) {
            gate.setRequired(false)
        }
        gate.setRequired(true)
        sessions.single().callback()
        assertTrue(sessions.single().cancelled)
        gate.close()
    }

    @Test fun failedStartDoesNotAdvertiseAnActiveSessionAndCanRetryOnNextEvent() {
        var attempts = 0
        val changes = mutableListOf<Boolean>()
        val gate = DemandTimer({ callback ->
            attempts++
            if (attempts == 1) error("injected scheduler failure")
            Session(callback)
        }, { changes += it }) {}
        assertTrue(runCatching { gate.setRequired(true) }.isFailure)
        assertTrue(changes.isEmpty())
        gate.setRequired(true)
        assertEquals(listOf(true), changes)
        gate.close()
        assertEquals(listOf(true, false), changes)
    }
}
