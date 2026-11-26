package com.vincenthzr.locationspoofer.utils

import com.vincenthzr.locationspoofer.data.model.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class AppleCellClientTest {
    private val fixture = "00000000000000000000b2011a08cc03100018c0c40720c1022a0c08909cdcb50b10e080e3922a"
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun checkAt(latitude: Double, mnc: Int, expected: Boolean) {
        if (expected) assertEquals(1, query(fixture, latitude, 113.1366, mnc).points.size)
        else assertThrows(RemoteCellNoCoverageException::class.java) { query(fixture, latitude, 113.1366, mnc) }
    }
    @Test fun aResponseFromAnotherOperatorIsNotAdopted() { checkAt(30.6545, 0, true); checkAt(30.6545, 1, false) }
    @Test fun aResponseBeyondTenKilometersIsNotAdopted() { checkAt(30.55, 0, false) }

    @Test fun seedFiltersApplyBeforeTakingTheNearestHundred() {
        val body = ByteArrayOutputStream().apply {
            write(ByteArray(10))
            for (id in 1L..100L) write(lteRecord(mnc = 1, id = id, latitude = 3_000_000_000L + id))
            for (id in 300L..400L) write(lteRecord(mnc = 0, id = id, latitude = 3_000_100_000L + id))
        }.toByteArray()
        assertEquals((300L..399L).toList(), query(body).points.map { it.identity.id })
    }

    @Test fun repeatedIdentityKeepsTheNearestInRangePosition() {
        val body = ByteArray(10) + lteRecord(0, 300, 3_010_000_000L) +
            lteRecord(0, 300, 3_005_000_000L) + lteRecord(0, 300, 3_000_100_000L)
        assertEquals(111.319, query(body).points.single().distanceMeters, 0.01)
    }

    private fun query(body: ByteArray, latitude: Double = 30.0, longitude: Double = 113.0,
        mnc: Int = 0): RemoteCellSnapshot {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(body.toResponseBody()).build()
        }.build()
        val seed = RemoteCellIdentity(RemoteCellRadio.LTE, 460, mnc, 321, 300)
        return try {
            runBlocking { AppleCellClient(http).query(latitude, longitude, RemoteWifiRegion.MAINLAND_CHINA, seed) }
        } finally {
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    private fun lteRecord(mnc: Long, id: Long, latitude: Long): ByteArray {
        fun varint(value: Long): ByteArray {
            var remaining = value
            return ByteArrayOutputStream().apply {
                while (remaining >= 128) {
                    write((remaining.toInt() and 127) or 128)
                    remaining = remaining ushr 7
                }
                write(remaining.toInt())
            }.toByteArray()
        }
        fun field(tag: Long, value: Long) = varint(tag) + varint(value)
        fun message(tag: Long, value: ByteArray) = field(tag, value.size.toLong()) + value
        val position = field(8, latitude) + field(16, 11_300_000_000L)
        return message(178, field(8, 460) + field(16, mnc) + field(24, id) + field(32, 321) + message(42, position))
    }
}
