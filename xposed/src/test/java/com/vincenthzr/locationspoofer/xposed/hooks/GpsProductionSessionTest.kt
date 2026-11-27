package com.vincenthzr.locationspoofer.xposed.hooks

import com.vincenthzr.locationspoofer.xposed.hooks.GpsProductionSession.Kind
import com.vincenthzr.locationspoofer.xposed.hooks.GpsProductionSession.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsProductionSessionTest {
    @Test
    fun firstAcceptedReportUsesTheSharedStartAndHeartbeatCannotRepeatIt() {
        val session = GpsProductionSession<String>()
        assertNull(session.updateDemand(false, ms(10)))
        assertEquals(Kind.STARTED, session.updateDemand(true, ms(80))?.kind)
        val epoch = session.pendingFirstFix()!!
        assertNull(session.updateDemand(true, ms(120)))

        val event = session.acceptFirstFix(epoch, ms(230), Source.PROVIDER_REPORT)!!
        assertEquals(Kind.FIRST_FIX, event.kind)
        assertEquals(ms(80), event.startElapsedNs)
        assertEquals(150, event.ttffMs)
        assertEquals(Source.PROVIDER_REPORT, event.source)
        assertNull(session.acceptFirstFix(epoch, ms(1080), Source.HEARTBEAT))
        assertNull(session.pendingFirstFix())
    }

    @Test
    fun stoppingAnEpisodeWithoutAnAdmittedFixDoesNotInventOne() {
        val session = GpsProductionSession<String>()
        session.updateDemand(false, ms(0))
        session.updateDemand(true, ms(20))
        val old = session.pendingFirstFix()!!
        val stop = session.updateDemand(false, ms(35))!!
        assertEquals(Kind.STOPPED, stop.kind)
        assertEquals(ms(20), stop.startElapsedNs)
        assertNull(session.updateDemand(false, ms(40)))
        assertEquals(Kind.STARTED, session.updateDemand(true, ms(100))?.kind)
        assertNull(session.acceptFirstFix(old, ms(110), Source.HEARTBEAT))
        assertEquals(40, session.acceptFirstFix(session.pendingFirstFix()!!, ms(140), Source.HEARTBEAT)?.ttffMs)
    }

    @Test
    fun unobservedStartStaysUnknownUntilARealDemandEdge() {
        val session = GpsProductionSession<String>()
        assertNull(session.updateDemand(true, ms(10)))
        assertNull(session.pendingFirstFix())
        assertNull(session.acceptFirstFix(null, ms(80), Source.PROVIDER_REPORT))
        assertNull(session.updateDemand(false, ms(100)))
        assertEquals(Kind.STARTED, session.updateDemand(true, ms(200))?.kind)
        assertEquals(0, session.acceptFirstFix(session.pendingFirstFix()!!, ms(200) + 999_999L, Source.HEARTBEAT)?.ttffMs)
    }

    @Test
    fun providerAdmissionBeforeObservedDemandCannotTurnALaterTickIntoFirstFix() {
        val session = GpsProductionSession<String>()
        session.updateDemand(false, ms(5))
        assertTrue(session.needsFixObservation())
        assertNull(session.acceptFirstFix(null, ms(60), Source.PROVIDER_REPORT))
        assertNull(session.updateDemand(true, ms(70)))
        assertFalse(session.needsFixObservation())
        assertNull(session.pendingFirstFix())
    }

    @Test
    fun inFlightAdmissionFromAStoppedEpochDoesNotHideTheNextStart() {
        val session = GpsProductionSession<String>()
        session.updateDemand(false, ms(0))
        session.updateDemand(true, ms(10))
        val old = session.pendingFirstFix()!!
        session.updateDemand(false, ms(30))
        assertNull(session.acceptFirstFix(old, ms(31), Source.HEARTBEAT))
        session.missAdmission(old)
        assertEquals(Kind.STARTED, session.updateDemand(true, ms(80))?.kind)
    }

    @Test
    fun admissionWhichStraddlesTheStartCannotBeAttributedToTheNewEpoch() {
        val session = GpsProductionSession<String>()
        session.updateDemand(false, ms(5))
        val notYetStarted = session.pendingFirstFix()
        session.updateDemand(true, ms(10))
        assertNull(session.acceptFirstFix(notYetStarted, ms(15), Source.PROVIDER_REPORT))
        assertNull(session.pendingFirstFix())
        assertEquals(Kind.STOPPED, session.updateDemand(false, ms(30))?.kind)
    }

    @Test
    fun anUnclassifiableAdmissionKeepsStartAndStopButSuppressesLaterFirstFix() {
        val session = GpsProductionSession<String>()
        session.updateDemand(false, ms(0))
        session.updateDemand(true, ms(30))
        val pending = session.pendingFirstFix()!!
        session.missAdmission(pending)
        assertNull(session.pendingFirstFix())
        assertNull(session.acceptFirstFix(pending, ms(40), Source.HEARTBEAT))
        assertEquals(Kind.STOPPED, session.updateDemand(false, ms(90))?.kind)
    }

    @Test
    fun publishedFixSurvivesReloadWithoutStartOrFixReplay() {
        val old = GpsProductionSession<String>()
        old.updateDemand(false, ms(0))
        old.updateDemand(true, ms(500))
        val epoch = old.pendingFirstFix()!!
        assertNotNull(old.acceptFirstFix(epoch, ms(800), Source.HEARTBEAT))
        val saved = old.pauseForReload()
        assertNull(old.nextDelivery())
        assertNull(old.acceptFirstFix(epoch, ms(900), Source.PROVIDER_REPORT))
        old.close()

        val next = GpsProductionSession<String>()
        next.restore(saved.clock, saved.unclaimed)
        assertNull(next.updateDemand(true, ms(1000)))
        assertNull(next.pendingFirstFix())
        val stop = next.updateDemand(false, ms(1100))!!
        assertEquals(Kind.STOPPED, stop.kind)
        assertEquals(ms(500), stop.startElapsedNs)
    }

    @Test
    fun pendingOrMissingReloadEvidenceDoesNotBackdateAnActiveEpisode() {
        val old = GpsProductionSession<String>()
        old.updateDemand(false, ms(0))
        old.updateDemand(true, ms(300))
        val saved = old.pauseForReload()
        old.cancelReload()
        assertFalse(old.needsFixObservation())
        assertNull(old.pendingFirstFix())
        assertNull(old.updateDemand(true, ms(400)))

        val next = GpsProductionSession<String>()
        next.restore(saved.clock, saved.unclaimed)
        assertNull(next.updateDemand(true, ms(600)))
        assertNull(next.pendingFirstFix())
        next.updateDemand(false, ms(800))
        assertEquals(Kind.STARTED, next.updateDemand(true, ms(900))?.kind)
    }

    @Test
    fun captureWinsBeforeClaimAndOnlyTheNewGenerationCanClaim() {
        val old = GpsProductionSession<String>()
        old.updateDemand(false, ms(0))
        old.updateDemand(true, ms(10))
        val event = old.acceptFirstFix(old.pendingFirstFix(), ms(50), Source.HEARTBEAT)!!
        old.enqueue(event, listOf("present-at-fix"))
        val prepared = old.nextDelivery()!!
        val saved = old.pauseForReload()
        assertFalse(old.claim(prepared))

        val next = GpsProductionSession<String>()
        next.restore(saved.clock, saved.unclaimed, pause = true)
        assertNull(next.nextDelivery())
        next.cancelReload()
        val transferred = next.nextDelivery()!!
        assertEquals(listOf("present-at-fix"), transferred.recipients)
        assertEquals(40, transferred.event.ttffMs)
        assertTrue(next.claim(transferred))
        assertNull(next.nextDelivery())
    }

    @Test
    fun claimWinsBeforeCaptureAndIsNotHandedOff() {
        val old = GpsProductionSession<String>()
        old.updateDemand(false, ms(0))
        old.enqueue(old.updateDemand(true, ms(10))!!, listOf("first"))
        val claimed = old.nextDelivery()!!
        assertTrue(old.claim(claimed))
        val saved = old.pauseForReload()
        assertTrue(saved.unclaimed.isEmpty())
        assertEquals(Kind.STARTED, claimed.event.kind)
    }

    @Test
    fun pausedStopAndNextStartRetainTheirOrderWhenHandoffIsCancelled() {
        val session = GpsProductionSession<String>()
        session.updateDemand(false, ms(0))
        session.updateDemand(true, ms(10))
        session.enqueue(session.updateDemand(false, ms(50))!!, listOf("first"))
        session.enqueue(session.updateDemand(true, ms(70))!!, listOf("first", "second"))
        val saved = session.pauseForReload()
        assertNull(session.nextDelivery())
        session.cancelReload()
        assertEquals(2, saved.unclaimed.size)
        val stop = session.nextDelivery()!!
        assertEquals(Kind.STOPPED, stop.event.kind)
        assertTrue(session.claim(stop))
        val start = session.nextDelivery()!!
        assertEquals(Kind.STARTED, start.event.kind)
        assertEquals(listOf("first", "second"), start.recipients)
        assertTrue(session.claim(start))
        assertNull(session.nextDelivery())
    }

    @Test
    fun transferredPermitAllowsOnlyTheClaimedBinderAndMethodAndClearsOnFailure() {
        val oldThreadLocal = ThreadLocal<Array<Any>>()
        val replacementThreadLocal = oldThreadLocal
        val binder = listOf("same-value")
        val equalButDifferentBinder = ArrayList(binder)
        val failure = IllegalStateException("callback")
        val actual = runCatching {
            withGpsLifecyclePermit(oldThreadLocal, binder, "onFirstFix", Any()) {
                assertTrue(matchesGpsLifecyclePermit(replacementThreadLocal, binder, "onFirstFix"))
                assertFalse(matchesGpsLifecyclePermit(replacementThreadLocal, equalButDifferentBinder, "onFirstFix"))
                assertFalse(matchesGpsLifecyclePermit(replacementThreadLocal, binder, "onGnssStopped"))
                throw failure
            }
        }.exceptionOrNull()
        assertEquals(failure, actual)
        assertFalse(matchesGpsLifecyclePermit(replacementThreadLocal, binder, "onFirstFix"))
    }

    private fun ms(value: Long): Long = value * 1_000_000L
}
