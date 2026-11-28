package com.vincenthzr.locationspoofer.ui.screen.managedata

import com.vincenthzr.locationspoofer.utils.CellOperatorEvidence
import org.junit.Assert.*
import org.junit.Test

class CellOperatorDisplayTest {
    private fun reported(mcc: String?, mnc: String?) = CellOperatorEvidence.fromReported(mcc, mnc)
    private fun source(type: String, evidence: CellOperatorEvidence?) = CellEvidenceDisplay.operatorLine(type, evidence)

    @Test fun old460AndZeroAreSavedValuesNotAReport() {
        val saved = EditableCellItem("fixture", "LTE", 460, 0)
        assertEquals("LTE • MCC:460 MNC:0", CellEvidenceDisplay.title(saved, "?"))
        assertEquals(CellEvidenceDisplay.OperatorLine.Unverified, source(saved.type, null))
    }

    @Test fun reportedZeroAndLeadingZerosAreLiteralCodesNotTheSavedInteger() {
        for (mnc in listOf("00", "000", "007")) {
            assertEquals(CellEvidenceDisplay.OperatorLine.Reported("001", mnc), source("WCDMA", reported("001", mnc)))
        }
    }

    @Test fun missingFieldsDoNotBorrowStoredValuesOrBecomeAnUnverifiedRecord() {
        assertEquals(CellEvidenceDisplay.OperatorLine.Reported(null, null), source("GSM", reported(null, null)))
        assertEquals(CellEvidenceDisplay.OperatorLine.Reported(null, "00"), source("LTE", reported(null, "00")))
        assertEquals(CellEvidenceDisplay.OperatorLine.Reported("460", null), source("NR", reported("460", null)))
    }

    @Test fun storedEditsCannotChangeWhatEarlierSampleReported() {
        val saved = EditableCellItem("fixture", "NR", 310, 260)
        assertEquals("NR • MCC:310 MNC:260", CellEvidenceDisplay.title(saved, "?"))
        assertEquals(CellEvidenceDisplay.OperatorLine.Reported("460", "00"), source(saved.type, reported("460", "00")))
    }

    @Test fun cdmaHasNoPlmnRowEvenIfMetadataWasSupplied() {
        assertEquals("CDMA", CellEvidenceDisplay.title(EditableCellItem("fixture", "cdma", 460, 0), "?"))
        assertNull(source("cdma", reported("460", "00")))
    }
}
