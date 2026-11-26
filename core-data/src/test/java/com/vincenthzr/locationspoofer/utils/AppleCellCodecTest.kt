package com.vincenthzr.locationspoofer.utils

import com.vincenthzr.locationspoofer.data.model.*
import org.junit.Assert.*
import org.junit.Test

class AppleCellCodecTest {
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val lte = hex("00000000000000000000b2011a08cc03100018c0c40720c1022a0c08909cdcb50b10e080e3922a")

    @Test fun decodesLteAndPreservesZeroMnc() {
        val point = AppleCellCodec.decode(lte, 30.6545, 113.1366).single()
        assertEquals(RemoteCellIdentity(RemoteCellRadio.LTE, 460, 0, 321, 123456), point.identity)
        assertEquals(30.6545, point.latitude, 1e-7)
        assertEquals(0.0, point.distanceMeters, 0.01)
    }

    @Test fun decodes36BitNrAndSignedSouthernLatitude() {
        val fixture = hex("00000000000000000000c2012208cc03100018ffffffffff0120c1022a110880fbb6b1f3ffffffff011080c8e3a938")
        val point = AppleCellCodec.decode(fixture, -33.86, 151.20).single()
        assertEquals(RemoteCellRadio.NR, point.identity.radio)
        assertEquals(68_719_476_735L, point.identity.id)
        assertEquals(-33.86, point.latitude, 1e-7)
    }

    @Test fun rejectsSentinelAndFarAwayRecords() {
        val sentinel = hex("00000000000000000000b2012408cc03100018c0c40720c1022a16088098f7f8bcffffffff01108098f7f8bcffffffff01")
        assertTrue(AppleCellCodec.decode(sentinel, 30.6545, 113.1366).isEmpty())
        assertTrue(AppleCellCodec.decode(lte, 0.0, 0.0).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsTruncatedMessages() {
        AppleCellCodec.decode(lte.copyOf(lte.size - 1), 30.6545, 113.1366)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsOverflowVarint() {
        AppleCellCodec.decode(ByteArray(10) + ByteArray(10) { 0xff.toByte() }, 30.6545, 113.1366)
    }

    @Test fun rejectsUnboundedDecodeRadius() {
        for (radius in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 20_001.0)) {
            assertThrows(IllegalArgumentException::class.java) {
                AppleCellCodec.decode(lte, 30.6545, 113.1366, maxDistanceMeters = radius)
            }
        }
    }

    @Test fun usesSeparateLteAndNrRequestFields() {
        fun contains(bytes: ByteArray, signature: ByteArray) = bytes.asList().windowed(signature.size).any { it == signature.asList() }
        val lteRequest = AppleCellCodec.request(RemoteCellIdentity(RemoteCellRadio.LTE, 460, 0, 321, 123456))
        val nrRequest = AppleCellCodec.request(RemoteCellIdentity(RemoteCellRadio.NR, 460, 0, 321, 68_719_476_735L))
        assertTrue(contains(lteRequest, hex("ca01"))) // field 25, wire 2
        assertTrue(contains(nrRequest, hex("ea01"))) // field 29, wire 2
        assertTrue(contains(nrRequest, hex("18ffffffffff01")))
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsLteIdentityOutside28Bits() {
        AppleCellCodec.request(RemoteCellIdentity(RemoteCellRadio.LTE, 460, 0, 321, 268_435_456L))
    }

    @Test fun modeledSignalsAreStableAndWeakenWithDistance() {
        assertTrue(RemoteSignalModel.wifi(10.0) > RemoteSignalModel.wifi(50.0))
        assertTrue(RemoteSignalModel.wifi(50.0) > RemoteSignalModel.wifi(673.0))
        assertEquals(RemoteSignalModel.wifi(673.0), RemoteSignalModel.wifi(673.0))
        assertTrue(RemoteSignalModel.cell(2000.0) in -140..-45)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsNonFiniteSignalDistance() {
        RemoteSignalModel.wifi(Double.NaN)
    }
}
