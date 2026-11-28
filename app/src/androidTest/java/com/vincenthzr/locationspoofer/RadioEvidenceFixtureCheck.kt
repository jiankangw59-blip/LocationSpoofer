package com.vincenthzr.locationspoofer

import android.os.Bundle
import com.vincenthzr.locationspoofer.utils.BleObservation
import com.vincenthzr.locationspoofer.utils.BleScanStatus
import com.vincenthzr.locationspoofer.utils.WifiObservationAge
import com.vincenthzr.locationspoofer.utils.capturedCellDbmForStorage
import org.json.JSONObject

/** Only in-memory fixtures; no context, radios, location, network, disk or simulation. */
internal fun checkRadioEvidenceFixtures(): Bundle {
    val out = Bundle()
    var passed = 0
    fun verify(label: String, condition: Boolean) {
        check(condition) { label }
        passed++
    }
    try {
        val nowUs = 25_000_000L
        verify("wifi current", WifiObservationAge.isRecent(nowUs, nowUs))
        verify("wifi age boundary", WifiObservationAge.isRecent(nowUs - 10_000_000L, nowUs))
        verify("wifi stale", !WifiObservationAge.isRecent(nowUs - 10_000_001L, nowUs))
        verify("wifi future", !WifiObservationAge.isRecent(nowUs + 1L, nowUs))
        verify("wifi unavailable", !WifiObservationAge.isRecent(0L, nowUs))

        val missing = JSONObject().put("dbm", 0).put("signalUnavailable", true)
        verify("cell explicit unknown", capturedCellDbmForStorage(missing) { error("estimated unknown signal") } == 0)
        val measured = JSONObject().put("dbm", -85)
        verify("cell measured -85", capturedCellDbmForStorage(measured) { measured.getInt("dbm") } == -85)
        verify("cell unmarked legacy", capturedCellDbmForStorage(JSONObject()) { -72 } == -72)

        val empty = BleObservation.completed("[]", 0)
        verify("ble completed empty", empty.status == BleScanStatus.EMPTY && empty.count == 0)
        val reports = """[{"fixture":true}]"""
        val observed = BleObservation.completed(reports, 1)
        verify("ble observed", observed.status == BleScanStatus.OBSERVED && observed.count == 1)
        verify("ble preserves supplied records", observed.recordsJson == reports)
        verify("ble failure differs from completed empty", BleObservation(BleScanStatus.FAILED).status != empty.status)
        out.putString("result", "PASS")
    } catch (e: Exception) {
        out.putString("result", "FAIL")
        out.putString("failure", e.message ?: e.javaClass.simpleName)
    }
    out.putInt("assertions", passed)
    return out
}
