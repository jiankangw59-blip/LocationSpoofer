package com.vincenthzr.locationspoofer.xposed.hooks.network

import com.vincenthzr.locationspoofer.utils.VisibleCells
import org.json.JSONObject

/** Shared projection for sync, request responses, listeners and derived radio state. */
internal object CellProfile {
    fun identity(cell: JSONObject): String {
        val radio=cell.optString("type",cell.optString("radio","LTE")).uppercase(java.util.Locale.ROOT)
        return listOf(radio,cell.optInt("mcc"),CellIdentityValues.mnc(cell),CellIdentityValues.area(cell,radio),
            CellIdentityValues.identity(cell,radio)).joinToString("/")
    }
    fun signal(cell: JSONObject): Int = listOf("dbm","averageSignalStrength","signal").firstNotNullOfOrNull { key ->
        cell.optInt(key,Int.MIN_VALUE).takeIf { it in -140..-40 }
    } ?: -100
    fun cells(config: JSONObject?): List<JSONObject> {
        val array=config?.optJSONArray("cell_json") ?: return emptyList()
        val pool=(0 until array.length()).mapNotNull { array.optJSONObject(it) }.filter { cell ->
            val radio=cell.optString("type",cell.optString("radio","LTE")).uppercase(java.util.Locale.ROOT)
            radio in setOf("LTE","NR","GSM","UMTS","WCDMA") && cell.optInt("mcc") in 1..999 &&
                CellIdentityValues.mnc(cell)!=null && CellIdentityValues.area(cell,radio)!=null &&
                CellIdentityValues.identity(cell,radio)!=null
        }
        val selected=VisibleCells.select(pool.map { cell -> VisibleCells.Candidate(identity(cell),signal(cell),
            cell.optBoolean("isRegistered"),cell.optDouble("distance_m",Double.NaN).takeIf { it.isFinite() && it>=0 }) })
        return selected.mapIndexed { index,source -> JSONObject(pool[source].toString()).put("isRegistered",index==0) }
    }
    fun network(config: JSONObject?): Int = when(cells(config).firstOrNull()?.let {
        it.optString("type", it.optString("radio", "LTE")).uppercase(java.util.Locale.ROOT)
    }) {
        "LTE"->13; "NR"->20; "GSM"->16; "WCDMA","UMTS"->3; else->0
    }
}
