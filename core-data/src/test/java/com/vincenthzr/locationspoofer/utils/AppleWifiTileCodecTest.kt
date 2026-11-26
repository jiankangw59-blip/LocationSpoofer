package com.vincenthzr.locationspoofer.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleWifiTileCodecTest {
    private val sample = "1a1412122881a681f005320a0dfee64412154e197043".bytes()

    @Test
    fun tileKeyMatchesKnownTileAndWrapsDateline() {
        assertEquals(98961500L, AppleWifiTileCodec.tileKey(30.6522, 113.1427))
        assertEquals(AppleWifiTileCodec.tileKey(0.0, -180.0), AppleWifiTileCodec.tileKey(0.0, 180.0))
    }

    @Test
    fun adjacentSearchIsBoundedUniqueAndStartsAtTheRequestedTile() {
        val keys = AppleWifiTileCodec.searchTileKeys(28.41075894581519, 117.59126442904986)
        assertEquals(listOf(98973607L, 98973606L, 98973618L, 98973605L, 98973613L,
            98973604L, 98973616L, 98973612L, 98973624L), keys)
        assertEquals(keys.size, keys.distinct().size)
    }

    @Test
    fun adjacentSearchWrapsDatelineAndDoesNotWrapPolarRows() {
        val dateline = AppleWifiTileCodec.searchTileKeys(0.0, -180.0)
        assertEquals(dateline, AppleWifiTileCodec.searchTileKeys(0.0, 180.0))
        assertEquals(AppleWifiTileCodec.tileKey(0.0, 179.99), dateline[1])
        assertEquals(6, AppleWifiTileCodec.searchTileKeys(AppleWifiTileCodec.MAX_LATITUDE, 0.0).size)
        assertEquals(6, AppleWifiTileCodec.searchTileKeys(-AppleWifiTileCodec.MAX_LATITUDE, 0.0).size)
    }

    @Test
    fun decodesSchemaFixtureSkipsUnknownFieldsAndDeduplicates() {
        val unknownFixed64 = "090000000000000000".bytes()
        val points = AppleWifiTileCodec.decode(unknownFixed64 + sample + sample, 30.6522, 113.1427)
        assertEquals(1, points.size)
        assertEquals("00:00:5e:00:53:01", points[0].bssid)
        assertEquals(30.650547, points[0].latitude, 1e-7)
        assertEquals(113.1419982, points[0].longitude, 1e-7)
        assertTrue(points[0].distanceMeters in 180.0..200.0)
    }

    @Test
    fun decodesSignedSouthernLatitudeAndFiltersDistantRecords() {
        val southern = "1a1412122882a681f005320a0d0008d0eb1548b5205a".bytes()
        val points = AppleWifiTileCodec.decode(southern, -33.8688, 151.2093)
        assertEquals(-33.8688, points.single().latitude, 1e-7)
        assertTrue(AppleWifiTileCodec.decode(sample, -33.8688, 151.2093).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTruncatedLengthDelimitedResponse() {
        AppleWifiTileCodec.decode("1affffffff7f".bytes(), 30.6522, 113.1427)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOverflowedVarint() {
        AppleWifiTileCodec.decode(ByteArray(10) { 0x80.toByte() }, 30.6522, 113.1427)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonFiniteCoordinates() {
        AppleWifiTileCodec.tileKey(Double.NaN, 113.1427)
    }

    private fun String.bytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
