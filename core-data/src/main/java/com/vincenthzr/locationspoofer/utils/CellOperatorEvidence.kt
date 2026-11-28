package com.vincenthzr.locationspoofer.utils

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** What this sample reported, not the operator the phone is or was registered with. */
class CellOperatorEvidence private constructor(val mcc: String?, val mnc: String?) {
    override fun equals(other: Any?) = other is CellOperatorEvidence && mcc == other.mcc && mnc == other.mnc
    override fun hashCode() = 31 * (mcc?.hashCode() ?: 0) + (mnc?.hashCode() ?: 0)
    companion object {
        fun fromReported(mcc: String?, mnc: String?) = CellOperatorEvidence(
            mcc?.takeIf { it.length == 3 && it != "000" && it.all { c -> c in '0'..'9' } },
            mnc?.takeIf { it.length in 2..3 && it.all { c -> c in '0'..'9' } }
        )
    }
}

// The .33 receipt blob is unchanged. This independently bounded optional attachment is stored
// atomically in the same row. It cannot create or renew a receipt, even when corrupt.
internal fun JournalPoint.encodeOperators(): ByteArray? {
    val included = entries.filterValues { it.cellOperator != null }
    if (included.isEmpty()) return null
    return ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeByte(1); out.writeShort(included.size)
            for ((key, seen) in included) {
                val op = requireNotNull(seen.cellOperator)
                out.writeUTF(key); out.writeLong(seen.round); out.writeLong(seen.time)
                out.writeUTF(op.mcc.orEmpty()); out.writeUTF(op.mnc.orEmpty())
            }
        }
        bytes.toByteArray()
    }
}

internal fun JournalPoint.attachOperators(bytes: ByteArray?): JournalPoint {
    if (bytes == null || bytes.size !in 3..(3 + JournalPoint.MAX_ENTRIES * 60)) return this
    try {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            if (input.readUnsignedByte() != 1) return this
            val n = input.readUnsignedShort()
            if (n !in 1..entries.size) return this
            val enriched = LinkedHashMap(entries)
            val unique = HashSet<String>()
            repeat(n) {
                val key = input.readUTF()
                val receipt = enriched[key] ?: return this
                if (!unique.add(key) || receipt.round != input.readLong() || receipt.time != input.readLong()) return this
                val mcc = input.readUTF().ifEmpty { null }; val mnc = input.readUTF().ifEmpty { null }
                val op = CellOperatorEvidence.fromReported(mcc, mnc)
                if (op.mcc != mcc || op.mnc != mnc) return this
                enriched[key] = receipt.copy(cellOperator = op)
            }
            if (input.read() != -1) return this
            return copy(entries = enriched)
        }
    } catch (_: IOException) { return this }
}
