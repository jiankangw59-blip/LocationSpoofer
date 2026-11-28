package com.vincenthzr.locationspoofer.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RefreshOutcomeTest {
    @Test fun cachedReadDoesNotImplyAnyRefreshAttempt() {
        val probe = RefreshProbe()
        assertEquals(RefreshOutcome.NOT_REQUESTED, probe.outcome)
        assertEquals(CaptureStatus.CACHED, captureStatus(ScanRead.SNAPSHOT, 2, 0))
        probe.localCooldown()
        assertEquals(RefreshOutcome.LOCAL_COOLDOWN, probe.outcome)
    }

    @Test fun unconfirmedRequestIsNotAttributedToPlatformThrottlingOrTimeout() = runBlocking {
        val probe = RefreshProbe()
        assertEquals(ScanRead.NOT_UPDATED, probe.track { wifiRefreshEvent(false) })
        assertEquals(RefreshOutcome.NOT_CONFIRMED, probe.outcome)
    }

    @Test fun timedOutWaitCanStillReadCacheWithoutMarkingItFresh() = runBlocking {
        val probe = RefreshProbe()
        assertNull(probe.track<ScanRead> { null })
        assertEquals(RefreshOutcome.TIMED_OUT, probe.outcome)
        assertEquals(CaptureStatus.CACHED, captureStatus(ScanRead.NOT_UPDATED, 2, 0))
    }

    @Test fun updateNotificationAloneDoesNotEstablishNewSamples() = runBlocking {
        val probe = RefreshProbe()
        val read = requireNotNull(probe.track { wifiRefreshEvent(true) })
        assertEquals(RefreshOutcome.COMPLETED, probe.outcome)
        assertEquals(CaptureStatus.EMPTY, captureStatus(read, 0, 0))
        assertEquals(CaptureStatus.NOT_UPDATED, captureStatus(read, 2, 0))
        assertEquals(CaptureStatus.OBSERVED, captureStatus(read, 2, 1))
        // A denied data read after the notification is not a denied refresh.
        val deniedRead = RadioCapture<String>("{}", CaptureStatus.PERMISSION_DENIED, refreshOutcome=probe.outcome)
        assertEquals(RefreshOutcome.COMPLETED, deniedRead.refreshOutcome)
        assertTrue(deniedRead.observedAtMs.isEmpty())
    }

    @Test fun errorCallbackAndPermissionExceptionRemainIndependentOfFallback() = runBlocking {
        val probe = RefreshProbe()
        val timeout = cellRefreshError(true, null)
        assertEquals(RefreshOutcome.TIMED_OUT, timeout)
        assertEquals(RefreshOutcome.PERMISSION_DENIED, cellRefreshError(false, SecurityException()))
        assertNull(probe.track<List<Int>> { RefreshEvent(cellRefreshError(false, null), null) })
        assertEquals(RefreshOutcome.FAILED, probe.outcome)
        assertEquals(CaptureStatus.CACHED, captureStatus(ScanRead.NOT_UPDATED, 1, 0))
        try { probe.track<Unit> { throw SecurityException() }; fail() } catch (_: SecurityException) {}
        assertEquals(RefreshOutcome.PERMISSION_DENIED, probe.outcome)
        assertEquals(CaptureStatus.CACHED, captureStatus(ScanRead.DENIED, 1, 0))
        try { probe.track<Unit> { throw IllegalStateException() }; fail() } catch (_: IllegalStateException) {}
        assertEquals(RefreshOutcome.FAILED, probe.outcome)
    }

    @Test fun cancellationIsNotInventedAsFailureOrTimeout() = runBlocking {
        val probe = RefreshProbe()
        val cancel = CancellationException("cancel")
        try { probe.track<Unit> { throw cancel }; fail() } catch (e: CancellationException) { assertSame(cancel, e) }
        assertEquals(RefreshOutcome.NOT_REQUESTED, probe.outcome)
    }
}