package com.vincenthzr.locationspoofer.xposed.hooks

import com.vincenthzr.locationspoofer.xposed.LocationHooker
import com.vincenthzr.locationspoofer.xposed.hooks.network.CellProfile
import com.vincenthzr.locationspoofer.xposed.hooks.network.buildFakeCellInfoList
import com.vincenthzr.locationspoofer.xposed.utils.XposedHelpers
import org.json.JSONObject

/** Copies recipient results; never rewrites TelephonyRegistry's cache or modem state. */
internal class RadioStateProjection(private val module: LocationHooker, private val loader: ClassLoader) {
    fun network(config: JSONObject): Int = if (config.optBoolean("mock_cell", true)) CellProfile.network(config) else 0

    fun service(original: Any, config: JSONObject): Any {
        val type = XposedHelpers.findClass("android.telephony.ServiceState", loader)
        val copy = type.getDeclaredConstructor(type).apply { isAccessible = true }.newInstance(original)
        val rat = network(config)
        val serving = serving(config)
        val identity = serving?.let { XposedHelpers.callMethod(it, "getCellIdentity") }
        val source = CellProfile.cells(config).firstOrNull()
        val operator = source?.let { "%d%02d".format(java.util.Locale.US, it.optInt("mcc"), it.optInt("mnc")) }.orEmpty()
        val sameOperator = operator == XposedHelpers.callMethod(original, "getOperatorNumeric")
        XposedHelpers.callMethod(copy, "setOperatorName",
            if (sameOperator) XposedHelpers.callMethod(original, "getOperatorAlphaLong") else "",
            if (sameOperator) XposedHelpers.callMethod(original, "getOperatorAlphaShort") else "", operator)
        val registrations = XposedHelpers.callMethod(copy, "getNetworkRegistrationInfoList") as List<*>
        for (entry in registrations.filterNotNull()) {
            if (XposedHelpers.callMethod(entry, "getTransportType") != 1) continue // Preserve IWLAN.
            val changed = entry.javaClass.getDeclaredConstructor(entry.javaClass).apply { isAccessible = true }.newInstance(entry)
            XposedHelpers.callMethod(changed, "setAccessNetworkTechnology", rat)
            XposedHelpers.callMethod(changed, "setNrState", 0)
            XposedHelpers.callMethod(changed, "setIsUsingCarrierAggregation", false)
            // Native fine-location redaction is authoritative. Never populate a redacted identity.
            val unredacted = XposedHelpers.callMethod(entry, "getCellIdentity") != null
            XposedHelpers.setObjectField(changed, "mCellIdentity", if (unredacted) identity else null)
            XposedHelpers.setObjectField(changed, "mRplmn", operator)
            XposedHelpers.callMethod(copy, "addNetworkRegistrationInfo", changed)
        }
        XposedHelpers.callMethod(copy, "setNrFrequencyRange", 0)
        XposedHelpers.callMethod(copy, "setCellBandwidths", IntArray(0))
        XposedHelpers.setIntField(copy, "mChannelNumber", Int.MAX_VALUE)
        return copy
    }

    fun signal(config: JSONObject): Any {
        val type = XposedHelpers.findClass("android.telephony.SignalStrength", loader)
        val result = XposedHelpers.newInstance(type)
        val cell = serving(config) ?: return result
        val strength = XposedHelpers.callMethod(cell, "getCellSignalStrength") ?: return result
        val field = when (strength.javaClass.simpleName) {
            "CellSignalStrengthLte" -> "mLte"
            "CellSignalStrengthNr" -> "mNr"
            "CellSignalStrengthGsm" -> "mGsm"
            "CellSignalStrengthWcdma" -> "mWcdma"
            "CellSignalStrengthCdma" -> "mCdma"
            else -> return result
        }
        XposedHelpers.setObjectField(result, field, strength)
        val stamp = (XposedHelpers.callMethod(cell, "getTimeStamp") as Long) / 1_000_000
        XposedHelpers.setLongField(result, "mTimestampMillis", stamp)
        return result
    }

    fun display(original: Any, config: JSONObject): Any {
        val copy = XposedHelpers.findClass("android.telephony.TelephonyDisplayInfo", loader)
        val roaming = XposedHelpers.callMethod(original, "isRoaming") as Boolean
        // LTE-only / standalone NR scenario; no evidence for carrier aggregation or EN-DC.
        return XposedHelpers.newInstance(copy, network(config), 0, roaming)
    }

    private fun serving(config: JSONObject): Any? = if (!config.optBoolean("mock_cell", true)) null else
        module.buildFakeCellInfoList(loader, config.optDouble("lat"), config.optDouble("lng"), config)
            .firstOrNull { XposedHelpers.callMethod(it, "isRegistered") == true }
}
