package com.vincenthzr.locationspoofer.utils

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CapturedCellStrengthTest {
    @Test fun missingOrSentinelDoesNotInvokeSignalEstimate() {
        for (dbm in listOf(null, 0, Int.MAX_VALUE, Int.MIN_VALUE, -157)) {
            val cell = JSONObject()
            putCapturedCellStrength(cell, dbm)
            val stored = JSONObject(cell.toString())
            assertEquals(0, stored.getInt("dbm"))
            assertEquals(0, capturedCellDbmForStorage(stored) { error("not measured") })
        }
    }

    @Test fun reportedNegativeSignalIncludingMinus85IsNotDiscarded() {
        val cell = JSONObject()
        putCapturedCellStrength(cell, null)
        for (dbm in listOf(-85, -100, -95)) {
            putCapturedCellStrength(cell, dbm)
            assertFalse(cell.has("signalUnavailable"))
            assertEquals(dbm, capturedCellDbmForStorage(cell) { cell.getInt("dbm") })
        }
    }

    @Test fun oldInputsWithoutProvenanceRemainOnExistingPolicy() {
        val legacy = JSONObject().put("dbm", -85)
        assertEquals(-85, capturedCellDbmForStorage(legacy) { legacy.getInt("dbm") })
        assertEquals(-70, capturedCellDbmForStorage(JSONObject()) { -70 })
    }
}
