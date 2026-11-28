package com.vincenthzr.locationspoofer.utils

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class LocalObservationJournalTest {
    private val a = "a2:b3:c4:d5:00:0a"
    private val b = "A2:B3:C4:D5:00:0B"
    private val seed = ByteArray(32) { it.toByte() }
    private val keys = ObservationKeys(seed, 7)
    private fun token(addr: String) = keys.key(ObservationKind.BLE, addr)!!

    @Test fun repeatedPointShowsOnlyBWithoutRedatingA() {
        val first = JournalPoint.EMPTY.advance(1, 250, mapOf(token(a) to 234L))
        val next = first.advance(2, 550, mapOf(token(b) to 501L))
        val actual = next.snapshot(550, keys)
        assertTrue(actual.hasRound)
        assertEquals(LocalObservationMark(false, 234), actual.lookup(ObservationKind.BLE, a))
        assertEquals(LocalObservationMark(true, 501), actual.lookup(ObservationKind.BLE, b))
        assertNull(actual.lookup(ObservationKind.WIFI, b))
    }
    @Test fun ticketNotWallTimeOrdersRoundsAndLateSaveCannotRefresh() {
        val now = JournalPoint.EMPTY.advance(20, 300, mapOf(token(a) to 300L))
            .advance(21, 50, mapOf(token(b) to 49L))
        assertSame(now, now.advance(20, 700, mapOf(token(a) to 700L)))
        assertSame(now, now.advance(21, 700, mapOf(token(a) to 700L)))
        assertTrue(now.snapshot(50, keys).lookup(ObservationKind.BLE, b)!!.inLatestRound)
    }
    @Test fun emptySavedRoundRetainsHistoryAndNeverClaimsUnknownPriorData() {
        val empty = JournalPoint.EMPTY
        assertFalse(empty.snapshot(9, keys).hasRound)
        val next = empty.advance(1, 100, mapOf(token(a) to 42L)).advance(2, 200, emptyMap())
        assertFalse(next.snapshot(200, keys).lookup(ObservationKind.BLE,a)!!.inLatestRound)
        assertNull(next.snapshot(200, keys).lookup(ObservationKind.BLE, b))
        assertFalse(next.snapshot(100, keys).hasRound)
    }
    @Test fun canonicalKeysAreScopedAndPlaceholdersHaveNoEvidence() {
        val k = token(a)
        assertEquals(k, token(a.uppercase().replace(':', '-')))
        assertNotEquals(k, keys.key(ObservationKind.WIFI, a))
        assertNotEquals(k, ObservationKeys(seed, 8).key(ObservationKind.BLE, a))
        assertNotEquals(k, ObservationKeys(ByteArray(32), 7).key(ObservationKind.BLE,a))
        listOf("", "02:00:00:00:00:00", "ff:ff:ff:ff:ff:ff", "not a mac").forEach {
            assertNull(keys.key(ObservationKind.BLE, it))
        }
    }
    @Test fun persistentPayloadContainsOnlyOpaqueKeysAndRejectsCorruption() {
        val p = JournalPoint.EMPTY.advance(1, 99, mapOf(token(a) to 44L))
        val encoded = p.encoded()
        assertFalse(String(encoded, Charsets.ISO_8859_1).contains(a, ignoreCase = true))
        assertEquals(p, JournalPoint.decode(1,99,encoded))
        assertThrows(IOException::class.java) { JournalPoint.decode(1,99,encoded + 0) }
    }
    @Test fun boundedHistoryKeepsNewestRoundAheadOfOldRows() {
        val raw = (0 until 600).associate { keys.key(ObservationKind.CELL, "LTE_$it")!! to 10L }
        val full = JournalPoint.EMPTY.advance(1, 40, raw)
        assertEquals(JournalPoint.MAX_ENTRIES, full.entries.size)
        val next = full.advance(2,50,mapOf(token(b) to 49L))
        assertEquals(JournalPoint.MAX_ENTRIES, next.entries.size)
        assertEquals(LocalObservationMark(true,49), next.snapshot(50, keys).lookup(ObservationKind.BLE,b))
        assertEquals(1, next.entries.values.count { it.round == 2L })
    }
}
