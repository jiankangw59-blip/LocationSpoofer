package com.vincenthzr.locationspoofer.xposed.utils

import org.junit.Assert.*
import org.junit.Test

class DemandMapTest {
    @Test fun rollbackReplacementAndConditionalMutationsWakeTheOwner() {
        var events = 0
        val map = DemandMap<String, String> { events++ }
        map["callback"] = "accepted"
        map["callback"] = "pending"
        assertTrue(map.replace("callback", "pending", "accepted"))
        assertEquals(3, events)
        assertFalse(map.replace("callback", "pending", "failed"))
        assertEquals(3, events)
        assertEquals("accepted", map.putIfAbsent("callback", "other"))
        assertEquals(3, events)
        map.remove("callback", "accepted")
        assertEquals(4, events)
    }

    @Test fun ownerFailureCannotBreakTheInterceptedRegistration() {
        val map = DemandMap<String, String> { error("owner unavailable") }
        map["callback"] = "registered"
        assertEquals("registered", map["callback"])
        assertEquals("registered", map.replace("callback", "updated"))
        assertEquals("updated", map.remove("callback"))
        assertTrue(map.isEmpty())
    }
}
