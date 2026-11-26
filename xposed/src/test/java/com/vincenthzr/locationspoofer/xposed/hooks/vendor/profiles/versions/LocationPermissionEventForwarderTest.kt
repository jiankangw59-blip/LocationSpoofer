package com.vincenthzr.locationspoofer.xposed.hooks.vendor.profiles.versions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LocationPermissionEventForwarderTest {
    @Test
    fun settledPermissionActivatesTheExistingRegistration() {
        val fixture = Fixture()
        fixture.native.deliver()
        assertFalse(fixture.native.active)
        assertEquals(0, fixture.native.callbacks)
        assertEquals(1, fixture.native.permissionChecks)

        fixture.native.permissionAllowed = true
        fixture.event()
        assertFalse(fixture.native.active)
        assertEquals(1, fixture.native.permissionChecks)

        fixture.drain()
        fixture.native.deliver()
        assertTrue(fixture.native.active)
        assertEquals(1, fixture.native.callbacks)
        assertEquals(2, fixture.native.permissionChecks)
        assertEquals(1, fixture.forwarded)
    }

    @Test
    fun deniedAndRevokedPermissionsRemainNativeDecisions() {
        val fixture = Fixture()
        fixture.event(FINE)
        fixture.drain()
        fixture.native.deliver()
        assertFalse(fixture.native.active)
        assertEquals(0, fixture.native.callbacks)

        fixture.native.permissionAllowed = true
        fixture.event()
        fixture.drain()
        fixture.native.deliver()
        assertEquals(1, fixture.native.callbacks)

        fixture.native.permissionAllowed = false
        fixture.event()
        fixture.drain()
        fixture.native.deliver()
        assertFalse(fixture.native.active)
        assertEquals(1, fixture.native.callbacks)
        assertEquals(4, fixture.native.permissionChecks)
        assertEquals(3, fixture.forwarded)
    }

    @Test
    fun nullAndNonLocationEventsDoNotAddAnotherNativeNotification() {
        val fixture = Fixture()
        fixture.event(pkg = null)
        fixture.event(pkg = "")
        fixture.event(op = "android:camera")
        fixture.event(op = null)
        assertTrue(fixture.queue.isEmpty())
        assertEquals(0, fixture.received)
        assertEquals(1, fixture.native.permissionChecks)
    }

    @Test
    fun checksTheCurrentTargetAndGenerationBeforeForwarding() {
        val fixture = Fixture()
        fixture.event(pkg = "com.example.other")
        fixture.drain()
        fixture.event()
        fixture.isTarget = false
        fixture.drain()
        fixture.isTarget = true
        fixture.event()
        fixture.moduleActive = false
        fixture.drain()
        fixture.event()
        assertTrue(fixture.queue.isEmpty())
        assertEquals(3, fixture.received)
        assertEquals(1, fixture.native.permissionChecks)
    }

    @Test
    fun closeClearsOwnedWorkAndPreventsACapturedTaskFromForwarding() {
        val fixture = Fixture()
        fixture.event()
        val stale = fixture.queue.single()

        fixture.forwarder.close()
        assertTrue(fixture.queue.isEmpty())
        stale.run()
        fixture.event()
        fixture.forwarder.close()

        assertEquals(1, fixture.clears)
        assertEquals(2, fixture.skippedClosed)
        assertEquals(1, fixture.native.permissionChecks)
    }

    @Test
    fun closingWhileTargetIsResolvedPreventsTheNativeNotification() {
        val fixture = Fixture()
        fixture.beforeTarget = { fixture.forwarder.close() }
        fixture.event()
        fixture.drain()
        assertEquals(1, fixture.skippedClosed)
        assertEquals(1, fixture.native.permissionChecks)
    }

    @Test
    fun closeDoesNotWaitForAnAdmittedNativeNotification() {
        val fixture = Fixture()
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        fixture.native.permissionAllowed = true
        fixture.beforeNotify = {
            entered.countDown()
            check(finish.await(5, TimeUnit.SECONDS))
        }
        fixture.event()
        val pending = fixture.queue.single()
        fixture.queue.clear()
        val worker = Thread { pending.run() }.apply {
            isDaemon = true
            start()
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            fixture.forwarder.close()
            fixture.event()
            assertTrue(fixture.queue.isEmpty())
        } finally {
            finish.countDown()
            worker.join(5_000)
        }
        assertFalse(worker.isAlive)
        assertEquals(0, fixture.failures)
        assertEquals(1, fixture.forwarded)
        assertEquals(1, fixture.skippedClosed)
        assertEquals(2, fixture.native.permissionChecks)
        assertTrue(fixture.native.active)
    }

    @Test
    fun enqueueAndNativeErrorsAreCountedWithoutPropagation() {
        val fixture = Fixture()
        fixture.acceptPost = false
        fixture.event()
        assertTrue(fixture.queue.isEmpty())
        fixture.acceptPost = true
        fixture.throwInNative = true
        fixture.event()
        fixture.drain()
        assertEquals(2, fixture.failures)
        assertEquals(0, fixture.forwarded)
    }

    private class Fixture {
        val queue = mutableListOf<Runnable>()
        val native = NativeRegistration()
        var moduleActive = true
        var isTarget = true
        var acceptPost = true
        var throwInNative = false
        var beforeTarget: () -> Unit = {}
        var beforeNotify: () -> Unit = {}
        var received = 0
        var forwarded = 0
        var skippedClosed = 0
        var failures = 0
        var clears = 0

        val forwarder = LocationPermissionEventForwarder<NativeRegistration>(
            post = { acceptPost && queue.add(it) },
            clear = {
                clears++
                queue.clear()
            },
            active = { moduleActive },
            target = { _, pkg ->
                beforeTarget()
                isTarget && pkg == PACKAGE
            },
            notifyNative = { registration, _ ->
                beforeNotify()
                check(!throwInNative)
                registration.refreshPermission()
            },
            onReceived = { received++ },
            onForwarded = { forwarded++ },
            onSkippedClosed = { skippedClosed++ },
            onFailure = { failures++ }
        )

        fun event(op: String? = COARSE, pkg: String? = PACKAGE) {
            forwarder.onChanged(op, pkg) { native }
        }

        fun drain() {
            val pending = queue.toList()
            queue.clear()
            pending.forEach { it.run() }
        }
    }

    private class NativeRegistration {
        var permissionAllowed = false
        var permissionChecks = 0
            private set
        var active = checkNativePermission()
            private set
        var callbacks = 0
            private set

        fun refreshPermission() {
            active = checkNativePermission()
        }

        private fun checkNativePermission(): Boolean {
            permissionChecks++
            return permissionAllowed
        }

        fun deliver() {
            if (active) callbacks++
        }
    }

    private companion object {
        const val PACKAGE = "com.example.locationprobe"
        const val COARSE = "android:coarse_location"
        const val FINE = "android:fine_location"
    }
}
