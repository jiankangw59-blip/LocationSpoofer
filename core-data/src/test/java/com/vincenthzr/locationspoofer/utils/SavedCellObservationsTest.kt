package com.vincenthzr.locationspoofer.utils

import org.junit.Assert.*
import org.junit.Test

class SavedCellObservationsTest {
    private val first = CellOperatorEvidence.fromReported("460", "00")
    private val second = CellOperatorEvidence.fromReported("460", "000")

    @Test fun preservesReportedZeroAndMostRecentTimeOnly() {
        val rows = SavedCellObservations()
        rows.accept("cell", 12, first)
        rows.accept("cell", 11, second)
        assertEquals(12L, rows.times["cell"])
        assertEquals("00", rows.operators["cell"]?.mnc)
        rows.accept("cell", 13, second)
        assertEquals("000", rows.operators["cell"]?.mnc)
    }
    @Test fun peersThatDisagreeAreUnknownRegardlessOfOrderAndCannotBeSpliced() {
        val a = CellOperatorEvidence.fromReported("460", null)
        val b = CellOperatorEvidence.fromReported(null, "00")
        for (reports in listOf(listOf(a, b, a), listOf(b, a, b), listOf(null, first), listOf(first, null))) {
            val rows = SavedCellObservations()
            for (op in reports) rows.accept("key", 8, op)
            assertEquals(8L, rows.times["key"])
            assertNull(rows.operators["key"])
            rows.accept("key", 9, second)
            assertEquals(second, rows.operators["key"])
        }
    }
    @Test fun explicitMissingIsDistinctFromNoEvidence() {
        val rows = SavedCellObservations()
        val missing = CellOperatorEvidence.fromReported(null, null)
        rows.accept("one", 76, missing)
        rows.accept("two", 76, null)
        assertEquals(missing, rows.operators["one"])
        assertNull(rows.operators["two"])
        rows.accept("one", 77, null)
        assertNull(rows.operators["one"])
    }
    @Test fun corroboratingPeersSurviveButInvalidTimesOrKeysDoNotCreateObservations() {
        val rows = SavedCellObservations()
        rows.accept("A", 5, first)
        rows.accept("A", 5, CellOperatorEvidence.fromReported("460", "00"))
        rows.accept("", 11, first)
        rows.accept("B", 0, second)
        assertEquals(mapOf("A" to first), rows.operators)
        assertEquals(mapOf("A" to 5L), rows.times)
    }
}