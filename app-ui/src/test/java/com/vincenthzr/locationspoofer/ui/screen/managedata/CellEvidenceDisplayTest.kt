package com.vincenthzr.locationspoofer.ui.screen.managedata

import org.junit.Assert.*
import org.junit.Test

class CellEvidenceDisplayTest {
    private val unknown = "?"
    private val lte = EditableCellItem("fixture", "LTE", 460, 0)

    @Test fun keepValidZeroIdentityAndNegativeSignal() {
        assertTrue(CellEvidenceDisplay.title(lte, unknown).contains("MNC:0"))
        val text = CellEvidenceDisplay.details(lte.copy(dbm = -85), unknown)
        for (field in listOf("TAC: 0", "CI: 0", "PCI: 0", "-85 dBm")) assertTrue(text, text.contains(field))
    }

    @Test fun keepRATSpecificIdentifierRanges() {
        val wide = lte.copy(tac = 65536, pci = 1007, nci = 68719476735L)
        val lteText = CellEvidenceDisplay.details(wide, unknown)
        assertTrue(lteText.contains("TAC: ?"))
        assertTrue(lteText.contains("PCI: ?"))
        val nrText = CellEvidenceDisplay.details(wide.copy(type = "NR"), unknown)
        for (field in listOf("TAC: 65536", "NCI: 68719476735", "PCI: 1007")) assertTrue(nrText, nrText.contains(field))
    }

    @Test fun sentinelsAreNotMeasurements() {
        for (dbm in listOf(0, Int.MAX_VALUE, Int.MIN_VALUE, -157)) {
            val row = lte.copy(type = "NR", tac = Int.MAX_VALUE, nci = Long.MAX_VALUE, pci = -1, dbm = dbm)
            val text = CellEvidenceDisplay.details(row, unknown)
            for (field in listOf("TAC: ?", "NCI: ?", "PCI: ?")) assertTrue(text, text.contains(field))
            assertFalse(text.contains("dBm"))
            assertFalse(text.contains(Int.MAX_VALUE.toString()))
            assertFalse(text.contains(Long.MAX_VALUE.toString()))
        }
    }

    @Test fun gsmWcdmaAndCdmaHaveTheirOwnIdentityFields() {
        val row = lte.copy(cid = 65536, psc = 511, systemId = 32767, networkId = 0, basestationId = -1)
        assertTrue(CellEvidenceDisplay.details(row.copy(type = "GSM"), unknown).contains("CID: ?"))
        val wcdma = CellEvidenceDisplay.details(row.copy(type = "WCDMA"), unknown)
        for (field in listOf("CID: 65536", "PSC: 511")) assertTrue(wcdma, wcdma.contains(field))
        val cdma = CellEvidenceDisplay.details(row.copy(type = "CDMA"), unknown)
        for (field in listOf("SID: 32767", "NID: 0", "BID: ?")) assertTrue(cdma, cdma.contains(field))
    }
}
