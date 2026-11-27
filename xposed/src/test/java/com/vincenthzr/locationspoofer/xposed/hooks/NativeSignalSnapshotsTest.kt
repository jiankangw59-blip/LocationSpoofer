package com.vincenthzr.locationspoofer.xposed.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicReference

class NativeSignalSnapshotsTest {
    @Test
    fun staleStatusSnapshotCannotReplaceACompletedRemoval() {
        verifyLaterNativeMutation(nmea = false, updated = emptyMap())
    }

    @Test
    fun staleNmeaSnapshotCannotReplaceACompletedInactivation() {
        verifyLaterNativeMutation(nmea = true, updated = mapOf("listener" to false))
    }

    private fun verifyLaterNativeMutation(nmea: Boolean, updated: Map<String, Boolean>) {
        val snapshots = NativeSignalSnapshots<String, Boolean>()
        val nativeLock = Any()
        val captured = CountDownLatch(1)
        val mutationAttempt = CountDownLatch(1)
        val refreshThread = AtomicReference<Thread>()
        val mutationThread = AtomicReference<Thread>()
        val executor = Executors.newFixedThreadPool(2)
        val active = mapOf("listener" to true)
        var native = active
        try {
            lateinit var oldRefresh: Future<*>
            lateinit var newerMutation: Future<*>
            // Pause the writer at the actual publication monitor after it has captured the old
            // state. A second thread must remain blocked at the native lock until publication.
            synchronized(snapshots) {
                oldRefresh = executor.submit {
                    refreshThread.set(Thread.currentThread())
                    snapshots.refresh(nativeLock, nmea) {
                        val old = native
                        captured.countDown()
                        old
                    }
                }
                assertTrue(captured.await(5, SECONDS))
                assertBlockedOn(refreshThread.get(), snapshots)
                newerMutation = executor.submit {
                    mutationThread.set(Thread.currentThread())
                    mutationAttempt.countDown()
                    synchronized(nativeLock) {
                        native = updated
                        snapshots.refresh(nativeLock, nmea) { native }
                    }
                }
                assertTrue(mutationAttempt.await(5, SECONDS))
                assertBlockedOn(mutationThread.get(), nativeLock)
            }
            oldRefresh.get(5, SECONDS)
            newerMutation.get(5, SECONDS)
            assertEquals(updated, snapshots.entries(nmea))
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, SECONDS))
            snapshots.close()
        }
    }

    private fun assertBlockedOn(thread: Thread, monitor: Any) {
        // Android's compile API omits java.lang.management. These unit tests run on the host JDK.
        val management = Class.forName("java.lang.management.ManagementFactory")
        val threads = management.getMethod("getThreadMXBean").invoke(null)
        val getThreadInfo = Class.forName("java.lang.management.ThreadMXBean")
            .getMethod("getThreadInfo", java.lang.Long.TYPE)
        val threadInfoType = Class.forName("java.lang.management.ThreadInfo")
        val getThreadState = threadInfoType.getMethod("getThreadState")
        val getLockInfo = threadInfoType.getMethod("getLockInfo")
        val deadline = System.nanoTime() + SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val info = getThreadInfo.invoke(threads, thread.id)
            if (info != null && getThreadState.invoke(info) == Thread.State.BLOCKED) {
                val lock = checkNotNull(getLockInfo.invoke(info))
                val lockInfoType = Class.forName("java.lang.management.LockInfo")
                assertEquals(monitor.javaClass.name, lockInfoType.getMethod("getClassName").invoke(lock))
                assertEquals(System.identityHashCode(monitor), lockInfoType.getMethod("getIdentityHashCode").invoke(lock))
                return
            }
            Thread.sleep(1)
        }
        error("Thread did not block on the expected monitor")
    }

    @Test
    fun closeClearsBothProvidersAndDiscardsARefreshAlreadyInFlight() {
        val snapshots = NativeSignalSnapshots<String, Any>()
        val nativeLock = Any()
        snapshots.refresh(nativeLock, false) { mapOf("status" to Any()) }
        snapshots.refresh(nativeLock, true) { mapOf("nmea" to Any()) }
        val captured = CountDownLatch(1)
        val releaseCapture = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val oldRefresh = executor.submit {
                snapshots.refresh(nativeLock, nmea = false) {
                    captured.countDown()
                    assertTrue(releaseCapture.await(5, SECONDS))
                    mapOf("late" to Any())
                }
            }
            assertTrue(captured.await(5, SECONDS))
            snapshots.close()
            assertTrue(snapshots.closed)
            assertTrue(snapshots.entries(false).isEmpty())
            assertTrue(snapshots.entries(true).isEmpty())
            releaseCapture.countDown()
            oldRefresh.get(5, SECONDS)
            snapshots.refresh(nativeLock, true) { error("Closed gate must not read registrations") }
            assertTrue(snapshots.entries(false).isEmpty())
            assertTrue(snapshots.entries(true).isEmpty())
        } finally {
            releaseCapture.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, SECONDS))
            snapshots.close()
        }
    }

    @Test
    fun failedCaptureReleasesOldEntriesForItsProviderAndPreservesTheFailure() {
        val snapshots = NativeSignalSnapshots<String, Boolean>()
        val nativeLock = Any()
        snapshots.refresh(nativeLock, false) { mapOf("status" to true) }
        snapshots.refresh(nativeLock, true) { mapOf("nmea" to true) }
        val failure = IllegalStateException("capture failed")
        assertSame(failure, runCatching {
            snapshots.refresh(nativeLock, false) { throw failure }
        }.exceptionOrNull())
        assertTrue(snapshots.entries(false).isEmpty())
        assertEquals(mapOf("nmea" to true), snapshots.entries(true))
        snapshots.clear()
        assertTrue(snapshots.entries(true).isEmpty())
        snapshots.close()
    }
}
