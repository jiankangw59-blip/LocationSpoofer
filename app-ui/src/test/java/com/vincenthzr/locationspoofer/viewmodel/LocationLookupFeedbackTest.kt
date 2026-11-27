package com.vincenthzr.locationspoofer.viewmodel

import com.vincenthzr.locationspoofer.data.model.LocationLookupFeedback
import com.vincenthzr.locationspoofer.data.model.LocationLookupLine
import com.vincenthzr.locationspoofer.data.model.LocationLookupSource.WIFI
import com.vincenthzr.locationspoofer.data.model.RemoteWifiMetadataStatus
import com.vincenthzr.locationspoofer.data.repository.WifiRepository
import com.vincenthzr.locationspoofer.ui.R
import com.vincenthzr.locationspoofer.utils.WigleWifiLookupResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationLookupFeedbackTest {
    @Test
    fun previousQueryAndDismissedCardCannotBeUpdatedByLateResults() {
        val old = LocationLookupFeedback(25.0, 105.0, emptyMap())
        val current = LocationLookupFeedback(25.001, 105.001, emptyMap())
        val line = LocationLookupLine(R.string.lookup_wifi_count, listOf(4))

        assertSame(current, current.updateLine(old, WIFI, line))
        assertFalse(current.matchesPoint(old.latitude, old.longitude))
        assertTrue(current.matchesPoint(25.0010005, 105.001))

        val dismissed = current.dismiss()
        assertSame(dismissed, dismissed.updateLine(current, WIFI, line))
        assertTrue(dismissed.lines.isEmpty())
        assertTrue(dismissed.blocksRepeat(current.latitude, current.longitude))
        assertFalse(dismissed.blocksRepeat(current.latitude, current.longitude, explicitlyStarted = true))
        assertFalse(dismissed.blocksRepeat(old.latitude, old.longitude))
        assertSame(current, current.updateLine(current.copy(identity = Any()), WIFI, line))
    }

    @Test
    fun freshWifiResultReplacesPendingLineAndPreviewIsDistinguished() {
        val pending = LocationLookupFeedback(
            25.0, 105.0, mapOf(WIFI to LocationLookupLine(R.string.lookup_wifi_pending))
        )
        val fresh = appleWifiLine(4)
        val completed = pending.updateLine(pending, WIFI, fresh)
        assertEquals(mapOf(WIFI to LocationLookupLine(R.string.lookup_wifi_count, listOf(4))), completed.lines)
        assertEquals(LocationLookupLine(R.string.lookup_wifi_preview, listOf(4)), appleWifiLine(4, true))
        assertEquals(LocationLookupLine(R.string.lookup_wifi_count, listOf(0)), appleWifiLine(0))
    }

    @Test
    fun metadataCountAndNoMatchAreDistinctFromAuthQuotaAndTransportErrors() {
        val found = result(RemoteWifiMetadataStatus.MATCHED)
        assertEquals(LocationLookupLine(R.string.lookup_metadata_count, listOf(2, 1, 0)), appleMetadataLine(found))
        assertEquals(
            LocationLookupLine(R.string.lookup_metadata_no_match),
            appleMetadataLine(result(RemoteWifiMetadataStatus.NO_MATCH))
        )
        assertEquals(
            LocationLookupLine(R.string.remote_wifi_metadata_auth, listOf(401)),
            appleMetadataLine(result(RemoteWifiMetadataStatus.UNAVAILABLE, 401))
        )
        assertEquals(
            LocationLookupLine(R.string.lookup_metadata_rate_limit),
            appleMetadataLine(result(RemoteWifiMetadataStatus.UNAVAILABLE, 429))
        )
        assertEquals(
            LocationLookupLine(R.string.lookup_metadata_failed),
            appleMetadataLine(result(RemoteWifiMetadataStatus.UNAVAILABLE))
        )
        assertTrue(appleMetadataLine(found.copy(reusedPreview = true)).fromPreview)
    }

    @Test
    fun fallbackDoesNotShowErrorsAsEmptyResults() {
        val messages = listOf(
            WigleWifiLookupResult.Records("[]") to R.string.lookup_wigle_no_data,
            WigleWifiLookupResult.HttpFailure(401) to R.string.lookup_wigle_auth,
            WigleWifiLookupResult.HttpFailure(429) to R.string.lookup_wigle_rate_limit,
            WigleWifiLookupResult.RequestFailed to R.string.lookup_wigle_request_failed,
            WigleWifiLookupResult.InvalidResponse to R.string.lookup_wigle_invalid_response
        )
        messages.forEach { (reply, text) -> assertEquals(text, wigleFallbackEmptyLine(reply).text) }
        assertEquals(listOf(401), wigleFallbackEmptyLine(WigleWifiLookupResult.HttpFailure(401)).arguments)
    }

    private fun result(status: RemoteWifiMetadataStatus, http: Int? = null): WifiRepository.AppleWifiImportResult =
        WifiRepository.AppleWifiImportResult(
            recordId = 1,
            metadataStatus = status,
            metadataHttpStatus = http,
            reusedPreview = false,
            accessPointCount = 4,
            metadataCount = 2,
            ssidCount = 1,
            frequencyCount = 0
        )
}
