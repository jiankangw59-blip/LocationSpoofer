package com.vincenthzr.locationspoofer.utils

import com.vincenthzr.locationspoofer.data.db.APPLE_WIFI_LINK_SOURCE
import com.vincenthzr.locationspoofer.data.db.LocationWifi
import com.vincenthzr.locationspoofer.data.db.LocationWithWifi
import com.vincenthzr.locationspoofer.data.db.WifiDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiRecordEvidenceTest {
    @Test fun missingSignalDoesNotBecomeReportedDbm() {
        for (dbm in listOf(0, -127, Int.MIN_VALUE, Int.MAX_VALUE)) assertFalse(WifiRecordEvidence.hasSignal(dbm))
        for (dbm in listOf(-126, -55, -1)) assertTrue(WifiRecordEvidence.hasSignal(dbm))
    }

    @Test fun estimateRequiresExplicitSourceAndUsableSignal() {
        assertEquals(WifiRecordEvidence.SignalKind.UNKNOWN, WifiRecordEvidence.signalKind(0, APPLE_WIFI_LINK_SOURCE))
        assertEquals(WifiRecordEvidence.SignalKind.ESTIMATE, WifiRecordEvidence.signalKind(-55, APPLE_WIFI_LINK_SOURCE))
        for (source in listOf(null, "", "OTHER"))
            assertEquals(WifiRecordEvidence.SignalKind.RECORD, WifiRecordEvidence.signalKind(-55, source))
    }

    @Test fun unchangedRadioRetainsOriginalLinkButManualEditsDoNotMasqueradeAsDirectoryData() {
        val device = WifiDevice(bssid = "fixture", ssid = "test", frequency = 0, capabilities = "")
        val link = LocationWifi(7L, device.bssid, -55, 2412, APPLE_WIFI_LINK_SOURCE)
        val previous = LocationWithWifi(link, device)
        assertSame(link, WifiRecordEvidence.linkForSave(previous, device, 7L, -55))
        for (updated in listOf(
            WifiRecordEvidence.linkForSave(previous, device, 7L, -56),
            WifiRecordEvidence.linkForSave(previous, device.copy(ssid = "edited"), 7L, -55),
            WifiRecordEvidence.linkForSave(previous, device, 8L, -55),
            WifiRecordEvidence.linkForSave(null, device, 7L, -55)
        )) {
            assertNull(updated.remoteSource)
            assertNull(updated.appleWlocDerivedFrequencyMHz)
        }
    }

    @Test fun noOpRoundTripDoesNotNeedAnSsid() {
        for (ssid in listOf("", "  spaced  ")) {
            val device = WifiDevice(bssid = "fixture", ssid = ssid, frequency = 0)
            val recorded = LocationWifi(9, device.bssid, -63, null, APPLE_WIFI_LINK_SOURCE)
            val link = WifiRecordEvidence.linkForSave(LocationWithWifi(recorded, device), device.copy(), 9, -63)
            assertSame(recorded, link)
        }
    }

    @Test fun missingFrequencyDoesNotBecomeReportedMhz() {
        for (mhz in listOf(-1, 0, Int.MAX_VALUE)) assertFalse(WifiRecordEvidence.hasFrequency(mhz))
        for (mhz in listOf(2412, 5180, 5955)) assertTrue(WifiRecordEvidence.hasFrequency(mhz))
    }
}
