package com.vincenthzr.locationspoofer.utils

import com.vincenthzr.locationspoofer.data.model.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WifiMetadataCodecTest {
    private val snapshot = RemoteWifiSnapshot(30.0, 113.0, RemoteWifiRegion.MAINLAND_CHINA, 1, 1,
        listOf(RemoteWifiPoint("a0:b1:c2:d3:e4:f5", 30.0, 113.0, 0.0)))
    private fun decode(fields: String) = WifiMetadataCodec.decode(JSONObject("""{"success":true,"results":[{"netid":"A0:B1:C2:D3:E4:F5","trilat":30.0,"trilong":113.0,$fields}]}"""), snapshot, 1_700_000_000_000L)

    @Test fun joinsExactBssidAndPreservesSourceFields() {
        val value = decode(""""ssid":"observed-ssid","frequency":5180,"channel":36,"encryption":"WPA2"""").values.single()
        assertEquals("observed-ssid", value.ssid)
        assertEquals(5180, value.frequencyMHz)
        assertEquals(36, value.channel)
        assertEquals("WPA2", value.encryption)
    }

    @Test fun aChannelWithoutBandDoesNotInventFrequency() {
        val value = decode(""""channel":6""").values.single()
        assertEquals(6, value.channel)
        assertNull(value.frequencyMHz)
        assertNull(value.ssid)
    }

    @Test fun inconsistentFrequencyAndChannelAreDiscarded() {
        val value = decode(""""ssid":"known","frequency":5180,"channel":6""").values.single()
        assertNull(value.frequencyMHz)
        assertNull(value.channel)
        assertEquals("known", value.ssid)
    }

    @Test fun validatesFrequencyGridsIncluding6Ghz() {
        for ((frequency, channel) in listOf(2412 to 1, 2484 to 14, 5180 to 36, 5825 to 165, 5935 to 2, 5955 to 1, 7115 to 233)) {
            assertEquals(channel, WifiMetadataCodec.frequencyToChannel(frequency))
            assertEquals(channel, MacVendorHelper.frequencyToChannel(frequency))
        }
        assertNull(WifiMetadataCodec.frequencyToChannel(2413))
        assertNull(WifiMetadataCodec.frequencyToChannel(0))
    }

    @Test fun ignoresOtherBssidsAndMovedAccessPoints() {
        assertTrue(WifiMetadataCodec.decode(JSONObject("""{"success":true,"results":[{"netid":"00:11:22:33:44:55","trilat":30,"trilong":113,"ssid":"other"},{"netid":"a0:b1:c2:d3:e4:f5","trilat":31,"trilong":113,"ssid":"moved"}]}"""), snapshot, 1).isEmpty())
    }

    @Test fun emptyNamesArePreservedButOverlongNamesAreNot() {
        assertEquals("", decode(""""ssid":""""").values.single().ssid)
        assertTrue(decode(""""ssid":"${"测".repeat(11)}"""").isEmpty())
    }

    @Test(expected = IllegalArgumentException::class) fun unsuccessfulApiResponseIsNotAValidEmptyResult() {
        WifiMetadataCodec.decode(JSONObject("""{"success":false,"results":[]}"""), snapshot, 1)
    }
}
