package com.vincenthzr.locationspoofer.utils

import com.vincenthzr.locationspoofer.data.model.RemoteWifiRegion
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.net.UnknownHostException
import java.util.Collections

class AppleWifiTileClientTest {
    private val sample = "1a1412122881a681f005320a0dfee64412154e197043"
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun lookup(response: (Int) -> Pair<Int, ByteArray>, check: (AppleWifiTileClient, List<Long>) -> Unit) {
        val calls = Collections.synchronizedList(mutableListOf<Long>())
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            calls.add(checkNotNull(request.header("X-tilekey")).toLong())
            val (status, bytes) = response(calls.size)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status)
                .message("fixture").body(bytes.toResponseBody()).build()
        }.build()
        try { check(AppleWifiTileClient(http), calls) }
        finally { http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
    }

    @Test fun aMissingCenterQueriesAnAdjacentTileAndKeepsTargetCacheIdentity() =
        lookup({ index -> if (index == 1) 404 to byteArrayOf() else 200 to sample }) { client, calls ->
            val result = runBlocking { client.query(30.6522, 113.1427, RemoteWifiRegion.MAINLAND_CHINA) }
            assertEquals(2, calls.size)
            assertEquals(1, result.points.size)
            assertEquals(98961500L, result.tileKey)
            assertTrue(result.points.all { it.distanceMeters <= 2000 })
        }

    @Test fun aValidEmptyTileAlsoQueriesTheNextTile() =
        lookup({ index -> 200 to if (index == 1) byteArrayOf() else sample }) { client, calls ->
            val result = runBlocking { client.query(30.6522, 113.1427, RemoteWifiRegion.MAINLAND_CHINA) }
            assertEquals(1, result.points.size); assertEquals(2, calls.size)
        }

    @Test fun missingTilesStopAtTheFiniteSearchLimit() = lookup({ 404 to byteArrayOf() }) { client, calls ->
        try {
            runBlocking { client.query(30.6522, 113.1427, RemoteWifiRegion.MAINLAND_CHINA) }
            fail("Expected an exhausted tile search")
        } catch (_: RemoteWifiNoCoverageException) { }
        assertEquals(9, calls.size); assertEquals(calls.size, calls.distinct().size)
    }

    @Test fun authorizationThrottlingAndServerErrorsAreNotRetriedAsMissingTiles() {
        for (status in listOf(401, 403, 429, 503)) lookup({ status to byteArrayOf() }) { client, calls ->
            try {
                runBlocking { client.query(30.6522, 113.1427, RemoteWifiRegion.MAINLAND_CHINA) }
                fail("Expected HTTP failure")
            } catch (error: RemoteWifiQueryException) { assertEquals(status, error.httpStatus) }
            assertEquals(1, calls.size)
        }
    }

    @Test fun malformedDataRetainsItsProtocolErrorAndStopsSearching() =
        lookup({ 200 to byteArrayOf(0x1a, 0x7f) }) { client, calls ->
            try {
                runBlocking { client.query(30.6522, 113.1427, RemoteWifiRegion.MAINLAND_CHINA) }
                fail("Expected a protobuf failure")
            } catch (_: RemoteWifiResponseException) { }
            assertEquals(1, calls.size)
        }

    @Test fun networkErrorsStopWithoutScanningAdditionalTiles() =
        lookup({ throw UnknownHostException("fixture offline") }) { client, calls ->
            try {
                runBlocking { client.query(30.6522, 113.1427, RemoteWifiRegion.MAINLAND_CHINA) }
                fail("Expected a network failure")
            } catch (_: UnknownHostException) { }
            assertEquals(1, calls.size)
        }
}
