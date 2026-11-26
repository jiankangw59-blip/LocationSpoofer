package com.vincenthzr.locationspoofer.xposed.utils

import org.junit.Assert.*
import org.junit.Test

class DemandListTest {
    @Test fun registrationAndFinalCancellationNotifyActualDemand() {
        val demand = mutableListOf<Boolean>()
        lateinit var listeners: DemandList<Any>
        listeners = DemandList { demand += listeners.isNotEmpty() }
        val a = Any()
        val b = Any()
        assertTrue(listeners.addIfAbsent(a))
        assertFalse(listeners.addIfAbsent(a))
        listeners.addIfAbsent(b)
        assertFalse(listeners.remove(Any()))
        listeners.removeAll { it === a }
        assertTrue(demand.last())
        listeners.removeAll { it === b }
        assertFalse(demand.last())
        assertTrue(listeners.isEmpty())
    }

    @Test fun bulkRestoreIsNotLostAndOldDeliverySnapshotRemainsStable() {
        var notifications = 0
        val listeners = DemandList<String> { notifications++ }
        listeners.addAll(listOf("a", "b"))
        val inFlightDelivery = listeners.iterator()
        listeners.clear()
        assertEquals(listOf("a", "b"), inFlightDelivery.asSequence().toList())
        assertEquals(2, notifications)
        listeners.clear()
        assertEquals(2, notifications)
    }

    @Test fun observerFailureCannotBreakApplicationRegistrationOrCancel() {
        val listeners = DemandList<String> { error("observer failure") }
        assertTrue(listeners.addIfAbsent("listener"))
        assertTrue(listeners.remove("listener"))
        assertTrue(listeners.isEmpty())
    }
}
