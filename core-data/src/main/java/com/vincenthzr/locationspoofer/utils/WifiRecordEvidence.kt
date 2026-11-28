package com.vincenthzr.locationspoofer.utils

import com.vincenthzr.locationspoofer.data.db.APPLE_WIFI_LINK_SOURCE
import com.vincenthzr.locationspoofer.data.db.LocationWifi
import com.vincenthzr.locationspoofer.data.db.LocationWithWifi
import com.vincenthzr.locationspoofer.data.db.WifiDevice

/** Presence checks for the Wi-Fi record editor, not signal or channel estimators. */
object WifiRecordEvidence {
    fun hasSignal(dbm: Int): Boolean = dbm in -126..-1
    enum class SignalKind { UNKNOWN, ESTIMATE, RECORD }

    fun signalKind(dbm: Int, source: String?): SignalKind = when {
        !hasSignal(dbm) -> SignalKind.UNKNOWN
        source == APPLE_WIFI_LINK_SOURCE -> SignalKind.ESTIMATE
        else -> SignalKind.RECORD
    }

    // A no-op edit (including only changing selection) must not erase import provenance.
    // Changed radio fields are a user record, not an untouched directory observation.
    fun linkForSave(previous: LocationWithWifi?, device: WifiDevice, locationId: Long, level: Int): LocationWifi =
        previous?.takeIf { it.locationWifi.locationId == locationId && it.device == device && it.locationWifi.level == level }?.locationWifi
            ?: LocationWifi(locationId = locationId, bssid = device.bssid, level = level)

    fun hasFrequency(mhz: Int): Boolean = mhz > 0 && mhz != Int.MAX_VALUE
}
