package com.vincenthzr.locationspoofer.xposed.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceAvailabilityDiscoveryTest {
    @Test
    fun startAndLateEventsReconcileOncePerLifecycleSignal() {
        var attempts = 0
        val discovery = ServiceAvailabilityDiscovery { attempts++ }
        discovery.request()
        assertEquals(0, attempts)
        discovery.start()
        discovery.start()
        assertEquals(1, attempts)
        discovery.request()
        assertEquals(2, attempts)
        assertTrue(discovery.closeIfIdle())
        discovery.request()
        assertEquals(2, attempts)
    }

    @Test
    fun eventsDuringDiscoveryCoalesceIntoOneFollowUpPass() {
        var attempts = 0
        lateinit var discovery: ServiceAvailabilityDiscovery
        discovery = ServiceAvailabilityDiscovery {
            if (++attempts == 1) {
                discovery.request()
                discovery.request()
            }
        }
        discovery.start()
        assertEquals(2, attempts)
    }

    @Test
    fun retiringWhileDiscoveryIsRunningIsRejectedUntilItFinishes() {
        var attempts = 0
        lateinit var discovery: ServiceAvailabilityDiscovery
        discovery = ServiceAvailabilityDiscovery {
            attempts++
            assertFalse(discovery.closeIfIdle())
        }
        discovery.start()
        assertTrue(discovery.closeIfIdle())
        discovery.request()
        assertEquals(1, attempts)
    }
}
