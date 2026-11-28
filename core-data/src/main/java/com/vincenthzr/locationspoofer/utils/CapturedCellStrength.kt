package com.vincenthzr.locationspoofer.utils

import org.json.JSONObject

/** Legacy CellLocation provides identity, not a field-strength measurement. Zero is the local
 * unavailable sentinel, not a signal level. Carry the reason through collection to storage. */
internal fun putCapturedCellStrength(out: JSONObject, reportedDbm: Int?) {
    if (reportedDbm != null && reportedDbm in -156..-1) {
        out.put("dbm", reportedDbm)
        out.remove("signalUnavailable")
    } else {
        out.put("dbm", 0)
        out.put("signalUnavailable", true)
    }
}

/** Scope the missing-value override to explicitly marked local collection data. */
fun capturedCellDbmForStorage(cell: JSONObject, existingPolicy: () -> Int): Int =
    if (cell.optBoolean("signalUnavailable", false)) 0 else existingPolicy()
