package com.vincenthzr.locationspoofer.ui.screen.managedata

import com.vincenthzr.locationspoofer.utils.LocalObservationMark
import org.junit.Assert.*
import org.junit.Test

class LocalObservationRowsTest {
    private val ids = listOf("a", "b", "saved-only")
    private val receipts = mapOf("a" to LocalObservationMark(false, 100L), "b" to LocalObservationMark(true, 200L))

    @Test fun latestViewKeepsOnlyBWhenAWasSeenEarlier() {
        val latest = projectObservations(ids, true, { it }, receipts::get)
        assertEquals(listOf("b"), latest.shown)
        assertEquals(100L, latest.marks.getValue("a").lastSeenAtMs)
    }

    @Test fun allViewPreservesHistoryAndUnknownSavedEntryWithoutInventingTime() {
        val all = projectObservations(ids, false, { it }, receipts::get)
        assertEquals(ids, all.shown)
        assertNull(all.marks["saved-only"])
        assertEquals(false, all.marks.getValue("a").inLatestRound)
    }

    @Test fun missingJournalDoesNotPromoteSavedEntries() {
        assertTrue(projectObservations(ids, true, { it }) { null }.shown.isEmpty())
        assertEquals(ids, projectObservations(ids, false, { it }) { null }.shown)
    }

    @Test fun editingMetadataOnlyDoesNotRoundCoordinatesAndInvalidateHistory() {
        assertEquals(12.3456789, storedOrEditedCoordinate("12.345679",12.3456789),0.0)
        assertEquals(12.3, storedOrEditedCoordinate("12.3",12.3456789),0.0)
    }
}
