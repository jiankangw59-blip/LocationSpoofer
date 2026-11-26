package com.vincenthzr.locationspoofer.utils

import org.json.JSONArray
import org.json.JSONObject

/** Both direct fallback and Apple seed discovery share one bounded search and its short-lived cache. */
class OpenCellIdClient(private val search: OpenCellSearchClient = OpenCellSearchClient()) {
    suspend fun validateToken(token: String): Boolean = search.validateToken(token)

    suspend fun fetchCellData(lat: Double, lng: Double, token: String): String {
        if (token.isBlank()) return "[]"
        val result = search.search(lat, lng, token)
        if (result.records.isEmpty() && !result.report.complete) throw OpenCellSearchIncompleteException()
        val cells = JSONArray()
        for (record in result.records) {
            // NB-IoT is counted in source diagnostics but is not relabelled as an LTE handset cell.
            if (record.radio == "NBIOT") continue
            val source = JSONObject(record.sourceJson)
            val pciMax = if (record.radio == "NR") 1007 else 503
            val pci = source.optInt("pci", Int.MAX_VALUE).takeIf { it in 0..pciMax } ?: Int.MAX_VALUE
            val observed = sequenceOf("dbm", "averageSignalStrength", "signal")
                .map { source.optInt(it, Int.MAX_VALUE) }.firstOrNull { it in -140..-40 }
            cells.put(JSONObject().apply {
                put("type", if (record.radio == "UMTS") "WCDMA" else record.radio)
                put("radio", record.radio); put("mcc", record.mcc); put("mnc", record.mnc)
                put("tac", record.area); put("lac", record.area)
                put("ci", record.cellId); put("cid", record.cellId); put("cellid", record.cellId)
                if (record.radio == "NR") put("nci", record.cellId)
                put("pci", pci); put("dbm", observed ?: RemoteSignalModel.cell(record.distanceMeters))
                put("isRegistered", false); put("lat", record.latitude); put("lon", record.longitude)
                put("source", "OpenCellID"); put("freshness", record.freshness.name)
                put("signal_source", if (observed == null) "DISTANCE_MODEL" else "RECORDED")
                record.updatedAtMillis?.let { put("updated", it / 1000) }
                record.samples?.let { put("samples", it) }
            })
        }
        return cells.toString()
    }
}
