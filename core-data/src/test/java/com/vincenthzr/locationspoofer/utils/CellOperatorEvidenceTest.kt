package com.vincenthzr.locationspoofer.utils

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.*
import org.junit.Test

class CellOperatorEvidenceTest {
    private val keys = ObservationKeys(ByteArray(32), 5)
    private val raw = "LTE_A"
    private val id = keys.key(ObservationKind.CELL, raw)!!
    private val op = CellOperatorEvidence.fromReported("001", "00")
    private fun mark(p: JournalPoint) = p.snapshot(p.revision, keys).lookup(ObservationKind.CELL, raw)

    @Test fun independentlyValidateAsciiCodesAndPreserveZeroWidth() {
        assertEquals("001", op.mcc)
        assertEquals("00", op.mnc)
        assertEquals("000", CellOperatorEvidence.fromReported("460","000").mnc)
        listOf("000", "46", "4601", " 460", "-01", "???").forEach {
            assertNull(CellOperatorEvidence.fromReported(it, "01").mcc)
            assertEquals("01", CellOperatorEvidence.fromReported(it, "01").mnc)
        }
        listOf(null, "", "0", "0000", "-1", "00 ", "??").forEach {
            assertNull(CellOperatorEvidence.fromReported("460", it).mnc)
            assertEquals("460", CellOperatorEvidence.fromReported("460", it).mcc)
        }
    }

    @Test fun historicalReceiptKeepsItsFieldsButNewObservationDoesNotInheritThem() {
        val first = JournalPoint.EMPTY.advance(1, 100, mapOf(id to 90L), mapOf(id to op))
        val second = first.advance(2, 200, emptyMap())
        assertEquals(LocalObservationMark(false, 90, op), mark(second))
        val third = second.advance(3, 300, mapOf(id to 275L))
        assertEquals(LocalObservationMark(true, 275), mark(third))
        assertSame(third, third.advance(1, 400, mapOf(id to 300L), mapOf(id to op)))
    }

    @Test fun explicitlyMissingBothAndMissingEvidenceRemainDistinctAcrossRoundTrip() {
        val absent = CellOperatorEvidence.fromReported(null, null)
        val saved = JournalPoint.EMPTY.advance(8, 80, mapOf(id to 70L), mapOf(id to absent))
        val old = JournalPoint.decode(8, 80, saved.encoded())
        assertNull(mark(old)!!.cellOperator)
        val restored = old.attachOperators(saved.encodeOperators())
        assertEquals(absent, mark(restored)!!.cellOperator)
        val one = restored.advance(9, 90, mapOf(id to 89L), mapOf(id to CellOperatorEvidence.fromReported(null, "001")))
        assertEquals("001", mark(JournalPoint.decode(9, 90, one.encoded()).attachOperators(one.encodeOperators()))!!.cellOperator!!.mnc)
    }

    @Test fun originalV33BlobMonotonicReceiptsRemainReadableWithoutFieldMigration() {
        val legacy = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use {
            it.writeInt(1); it.writeUTF(id); it.writeLong(1); it.writeLong(95)
        } }.toByteArray()
        val old = JournalPoint.decode(1, 100, legacy)
        assertEquals(LocalObservationMark(true, 95), mark(old))
        assertArrayEquals(legacy, old.encoded())
        assertNull(old.encodeOperators())
        val fresh = old.advance(2, 200, mapOf(id to 185L), mapOf(id to op))
        assertEquals(op, mark(JournalPoint.decode(2,200,fresh.encoded()).attachOperators(fresh.encodeOperators()))!!.cellOperator)
    }

    @Test fun noFreshIdWrongRevisionOrOtherRadioCannotCertifyFields() {
        val wifiRaw = "02:00:00:00:00:0a"
        val wifi = keys.key(ObservationKind.WIFI, wifiRaw)!!
        val p = JournalPoint.EMPTY.advance(1, 100, mapOf(wifi to 92L), mapOf(wifi to op, id to op))
        assertNull(mark(p))
        assertNull(p.snapshot(101,keys).lookup(ObservationKind.WIFI, wifiRaw))
        assertEquals(LocalObservationMark(true, 92), p.snapshot(100,keys).lookup(ObservationKind.WIFI, wifiRaw))
    }

    @Test fun corruptOrWrongSampleAttachmentNeverChangesAnyObservationTime() {
        val p = JournalPoint.EMPTY.advance(1, 101, mapOf(id to 9L), mapOf(id to op))
        val bytes = p.encodeOperators()!!
        val base = JournalPoint.decode(1, 101, p.encoded())
        val corruptField = bytes.copyOf().apply { this[lastIndex] = 42 }
        for (broken in listOf(bytes.copyOf(bytes.size-1), bytes + 0, corruptField, ByteArray(40_000))) {
            assertSame(base,base.attachOperators(broken))
            assertEquals(LocalObservationMark(true, 9), mark(base))
        }
        val next = base.advance(2, 202, mapOf(id to 40L))
        assertSame(next, next.attachOperators(bytes))
        assertNull(mark(next)!!.cellOperator)
    }

    @Test fun fieldReceiptIsEvictedWithTimeAndCannotBeReintroducedByOldEvidence() {
        val old = JournalPoint.EMPTY.advance(1, 80, mapOf(id to 79L), mapOf(id to op))
        val other = (0 until JournalPoint.MAX_ENTRIES).associate { keys.key(ObservationKind.CELL, "N_$it")!! to 95L }
        val fresh = old.advance(2, 100, other, mapOf(id to op))
        assertNull(mark(fresh)); assertNull(fresh.encodeOperators())
    }
}
