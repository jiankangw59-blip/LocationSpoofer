package com.vincenthzr.locationspoofer.xposed.hooks.network

import org.json.JSONObject

/** Integer identity fields must survive JSON and framework construction without narrowing. */
internal object CellIdentityValues {
    const val MAX_NCI = 68_719_476_735L

    fun nrIdentity(cell: JSONObject): Long? = if (cell.has("nci") && !cell.isNull("nci")) {
        value(cell, MAX_NCI, "nci")
    } else value(cell, MAX_NCI, "cellid", "cell", "ci", "cid")

    fun area(cell: JSONObject, radio: String): Int? {
        val keys = if (radio == "GSM" || radio == "WCDMA" || radio == "UMTS") {
            arrayOf("lac", "area", "tac")
        } else arrayOf("tac", "area", "lac")
        return value(cell, if (radio == "NR") 16_777_215L else 65_535L, *keys)?.toInt()
    }

    fun mnc(cell: JSONObject): Int? = value(cell, 999L, "mnc", "net")?.toInt()

    fun identity(cell: JSONObject, radio: String): Long? = if (radio == "NR") nrIdentity(cell) else
        value(cell, if (radio == "GSM") 65_535L else 268_435_455L,
            *(if (radio == "GSM" || radio == "UMTS" || radio == "WCDMA") arrayOf("cid", "ci", "cellid", "cell")
                else arrayOf("ci", "cid", "cellid", "cell")))

    private fun value(cell: JSONObject, maximum: Long, vararg keys: String): Long? {
        for (key in keys) {
            if (!cell.has(key) || cell.isNull(key)) continue
            val number = cell.opt(key)?.toString()?.toLongOrNull() ?: continue
            if (number in 0..maximum) return number
        }
        return null
    }
}
