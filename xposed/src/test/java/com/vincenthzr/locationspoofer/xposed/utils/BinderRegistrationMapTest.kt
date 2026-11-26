package com.vincenthzr.locationspoofer.xposed.utils

import android.os.IBinder
import java.lang.reflect.Proxy
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class BinderRegistrationMapTest {
    @Test
    fun unregisterCyclesUnlinkAllRecipients() {
        val registrations = BinderRegistrationMap<Any>(BinderDeathRegistry())
        val shared = FakeBinder()
        repeat(1_000) {
            registrations[shared.binder] = Any()
            assertNotNull(registrations.remove(shared.binder))
        }
        assertTrue(registrations.isEmpty())
        assertTrue(shared.recipients.isEmpty())
        assertEquals(1_000, shared.unlinks)
        repeat(1_000) {
            val binder = FakeBinder()
            registrations[binder.binder] = Any()
            registrations.remove(binder.binder)
            assertTrue(binder.recipients.isEmpty())
            assertEquals(1, binder.unlinks)
        }
        assertTrue(registrations.isEmpty())
    }

    @Test
    fun replacementAndRemovalAreSpecificToRecordAndRole() {
        val deaths = BinderDeathRegistry()
        val status = BinderRegistrationMap<Record>(deaths)
        val nmea = BinderRegistrationMap<Record>(deaths)
        val client = FakeBinder()
        val original = Record("request")
        val replacement = original.copy()
        status[client.binder] = original
        val stale = client.recipients.single()
        nmea[client.binder] = original
        status[client.binder] = replacement
        assertEquals(2, client.recipients.size)
        assertEquals(1, client.unlinks)
        stale.binderDied()
        assertNull(status.remove(client.binder, original))
        assertSame(replacement, status[client.binder])
        assertSame(original, nmea[client.binder])

        assertSame(replacement, status.remove(client.binder))
        assertEquals(1, client.recipients.size)
        assertSame(original, nmea[client.binder])
        client.die()
        assertTrue(nmea.isEmpty())
    }

    @Test
    fun failedReplacementCanRestorePreviousRecord() {
        val registrations = BinderRegistrationMap<Any>(BinderDeathRegistry())
        val client = FakeBinder()
        val original = Any()
        val pending = Any()
        registrations[client.binder] = original
        assertSame(original, registrations.put(client.binder, pending))
        registrations.rollback(client.binder, pending, original)
        assertSame(original, registrations[client.binder])
        assertEquals(1, client.recipients.size)
        assertEquals(2, client.unlinks)
    }

    @Test
    fun unsuccessfulRollbackStillReleasesRejectedRegistration() {
        val registrations = BinderRegistrationMap<Any>(BinderDeathRegistry())
        val client = FakeBinder()
        val original = Any()
        val pending = Any()
        registrations[client.binder] = original
        registrations[client.binder] = pending
        client.rejectLinks = true

        registrations.rollback(client.binder, pending, original)

        assertTrue(registrations.isEmpty())
        assertTrue(client.recipients.isEmpty())
    }

    @Test
    fun failedDeathLinkKeepsTheCurrentRegistration() {
        val removed = mutableListOf<Any>()
        val registrations = BinderRegistrationMap<Any>(BinderDeathRegistry(), onRemoved = { removed.add(it) })
        val client = FakeBinder()
        val original = Any()
        val replacement = Any()
        registrations[client.binder] = original
        val originalLink = client.recipients.single()
        client.rejectLinks = true

        assertSame(original, registrations.put(client.binder, replacement))
        assertFalse(registrations.replace(client.binder, original, replacement))
        assertSame(original, registrations[client.binder])
        assertEquals(setOf(originalLink), client.recipients)
        assertTrue(removed.isEmpty())
    }

    @Test
    fun inFlightDeathDoesNotRemoveAReinstalledValue() {
        val deaths = BinderDeathRegistry()
        val client = FakeBinder()
        val other = FakeBinder()
        val original = Any()
        val pending = Any()
        val gate = Any()
        lateinit var originalLink: IBinder.DeathRecipient
        lateinit var callback: Thread
        lateinit var registrations: BinderRegistrationMap<Any>
        registrations = BinderRegistrationMap(deaths, onRemoved = {
            if (it === gate) {
                client.recipients.remove(originalLink)
                callback = thread(isDaemon = true) { originalLink.binderDied() }
                val deadline = System.nanoTime() + 1_000_000_000L
                while (callback.state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                    Thread.yield()
                }
                assertEquals(Thread.State.BLOCKED, callback.state)
                registrations[client.binder] = pending
                registrations.rollback(client.binder, pending, original)
            }
        })
        registrations[client.binder] = original
        originalLink = client.recipients.single()
        registrations[other.binder] = gate
        registrations.remove(other.binder)
        callback.join(1_000)

        assertFalse(callback.isAlive)
        assertSame(original, registrations[client.binder])
        assertEquals(1, client.recipients.size)
    }

    @Test
    fun partialLinkFailureAndImmediateDeathDoNotPublishRecords() {
        val registrations = BinderRegistrationMap<Any>(BinderDeathRegistry())
        val partiallyLinked = FakeBinder().apply { rejectAfterLink = true }
        val dead = FakeBinder().apply { dieDuringLink = true }

        assertFalse(registrations.registerIfAbsent(partiallyLinked.binder, Any()))
        assertTrue(partiallyLinked.recipients.isEmpty())
        assertFalse(registrations.registerIfAbsent(dead.binder, Any()))
        assertTrue(dead.recipients.isEmpty())
        assertTrue(registrations.isEmpty())
    }

    @Test
    fun failedLinkAndGenerationCloseLeaveNoLinks() {
        val deaths = BinderDeathRegistry()
        val registrations = BinderRegistrationMap<Any>(deaths)
        val failed = FakeBinder().apply { rejectLinks = true }
        assertFalse(registrations.registerIfAbsent(failed.binder, Any()))
        assertTrue(registrations.isEmpty())
        assertTrue(failed.recipients.isEmpty())

        val live = FakeBinder()
        registrations[live.binder] = Any()
        deaths.close()
        assertEquals(1, live.unlinks)
        assertTrue(live.recipients.isEmpty())
        registrations.clear()
        registrations[live.binder] = Any()
        assertEquals(1, live.unlinks)
        assertTrue(registrations.isEmpty())
    }

    @Test
    fun nativeRegistrationRejectionReleasesOnlyItsEntries() {
        val registration = LocationRegistrationFixture()
        val rejected = registration.track("original")
        val failure = SecurityException("native permission denied")
        assertSame(failure, runCatching { rejected.proceed { throw failure } }.exceptionOrNull())
        assertTrue(registration.binders.isEmpty())
        assertTrue(registration.client.recipients.isEmpty())
        assertTrue(registration.listeners.isEmpty())
        assertTrue(registration.intents.isEmpty())

        assertEquals(false, registration.track("false return").proceed { false })
        assertTrue(registration.binders.isEmpty())
        assertTrue(registration.client.recipients.isEmpty())
        assertTrue(registration.listeners.isEmpty())
        assertTrue(registration.intents.isEmpty())
    }

    @Test
    fun nativeRegistrationRestoresEarlierAcceptedEntryAfterOverlappingFailures() {
        val registration = LocationRegistrationFixture()
        var calls = 0
        assertNull(registration.track("accepted").proceed { calls++; null })
        assertEquals(1, calls)

        val first = registration.track("first attempt")
        val second = registration.track("second attempt")
        assertEquals(false, first.proceed { false })
        assertEquals("second attempt", registration.current())
        val failure = IllegalArgumentException("native rejected replacement")
        assertSame(failure, runCatching { second.proceed { throw failure } }.exceptionOrNull())
        assertEquals("accepted", registration.current())
        assertEquals(1, registration.client.recipients.size)
    }

    @Test
    fun nativeRegistrationFailurePreservesReentrantSuccessAndOriginalResult() {
        val registration = LocationRegistrationFixture()
        val outer = registration.track("outer")
        val nativeResult = Any()
        assertEquals(false, outer.proceed {
            assertSame(nativeResult, registration.track("inner").proceed { nativeResult })
            false
        })
        assertEquals("inner", registration.current())
        assertEquals(1, registration.client.recipients.size)
    }

    private class LocationRegistrationFixture {
        val client = FakeBinder()
        val binders = BinderRegistrationMap<NativeRegistration<String>>(BinderDeathRegistry())
        val listeners = java.util.WeakHashMap<Any, NativeRegistration<String>>()
        val intents = java.util.concurrent.ConcurrentHashMap<Any, NativeRegistration<String>>()
        private val key = Any()
        private val lock = Any()

        fun track(value: String): NativeRegistrationAttempt = NativeRegistrationAttempt(lock).apply {
            track(value, { binders.put(client.binder, it) }) { rejected, previous ->
                binders.rollback(client.binder, rejected, previous)
            }
            for (map in listOf(listeners, intents)) {
                track(value, { map.put(key, it) }) { rejected, previous ->
                    if (previous == null) map.remove(key, rejected)
                    else map.replace(key, rejected, previous)
                }
            }
        }

        fun current(): String? {
            val value = binders[client.binder]?.value
            assertEquals(value, listeners[key]?.value)
            assertEquals(value, intents[key]?.value)
            return value
        }
    }

    private data class Record(val request: String)

    private class FakeBinder {
        var rejectLinks = false
        var rejectAfterLink = false
        var dieDuringLink = false
        val recipients = linkedSetOf<IBinder.DeathRecipient>()
        var unlinks = 0
            private set

        val binder = Proxy.newProxyInstance(IBinder::class.java.classLoader, arrayOf(IBinder::class.java)) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                "linkToDeath" -> {
                    if (rejectLinks) throw IllegalStateException("link failed")
                    recipients.add(args!![0] as IBinder.DeathRecipient)
                    if (rejectAfterLink) throw IllegalStateException("link failed after registration")
                    if (dieDuringLink) die()
                    null
                }
                "unlinkToDeath" -> {
                    unlinks++
                    recipients.remove(args!![0] as IBinder.DeathRecipient)
                }
                else -> error("Unexpected Binder method: ${method.name}")
            }
        } as IBinder

        fun die() {
            val pending = recipients.toList()
            recipients.clear()
            pending.forEach { it.binderDied() }
        }
    }
}
