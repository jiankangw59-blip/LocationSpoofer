package com.vincenthzr.locationspoofer.ui.screen.managedata

import com.vincenthzr.locationspoofer.utils.CellOperatorEvidence
import java.util.Locale

/** Display only: never repair stored identities, infer measurement provenance, or rewrite a model. */
internal object CellEvidenceDisplay {
    private fun id(value: Int, max: Int, unknown: String, min: Int = 0): String =
        if (value in min..max) value.toString() else unknown

    fun isReportableDbm(value: Int): Boolean = value in -156..-1

    fun title(c: EditableCellItem, unknown: String): String {
        val radio = c.type.uppercase(Locale.ROOT)
        return if (radio == "CDMA") radio else
            "$radio • MCC:${id(c.mcc, 999, unknown, 1)} MNC:${id(c.mnc, 999, unknown)}"
    }

    sealed interface OperatorLine {
        object Unverified : OperatorLine
        data class Reported(val mcc: String?, val mnc: String?) : OperatorLine
    }

    // A stored value (including 460/0) cannot establish what the sample actually reported.
    fun operatorLine(type: String, evidence: CellOperatorEvidence?): OperatorLine? =
        when (type.uppercase(Locale.ROOT)) {
            "LTE", "NR", "GSM", "WCDMA" -> if (evidence == null) OperatorLine.Unverified
                else OperatorLine.Reported(evidence.mcc, evidence.mnc)
            else -> null
        }

    fun details(c: EditableCellItem, unknown: String): String {
        val radio = c.type.uppercase(Locale.ROOT)
        val ids = when (radio) {
            "LTE" -> "TAC: ${id(c.tac, 65535, unknown)}  •  CI: ${id(c.ci, 268435455, unknown)}  •  PCI: ${id(c.pci, 503, unknown)}"
            "NR" -> {
                val nci = if (c.nci in 0L..68719476735L) c.nci.toString() else unknown
                "TAC: ${id(c.tac, 16777215, unknown)}  •  NCI: $nci  •  PCI: ${id(c.pci, 1007, unknown)}"
            }
            "GSM" -> "LAC: ${id(c.lac, 65535, unknown)}  •  CID: ${id(c.cid, 65535, unknown)}"
            "WCDMA" -> "LAC: ${id(c.lac, 65535, unknown)}  •  CID: ${id(c.cid, 268435455, unknown)}  •  PSC: ${id(c.psc, 511, unknown)}"
            "CDMA" -> "SID: ${id(c.systemId, 32767, unknown)}  •  NID: ${id(c.networkId, 65535, unknown)}  •  BID: ${id(c.basestationId, 65535, unknown)}"
            else -> "Key: ${c.cellKey}"
        }
        val signal = if (isReportableDbm(c.dbm)) "${c.dbm} dBm" else unknown
        return "$ids  •  $signal"
    }
}
