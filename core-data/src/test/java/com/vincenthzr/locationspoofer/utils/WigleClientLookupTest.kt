package com.vincenthzr.locationspoofer.utils

import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

class WigleClientLookupTest {
    @Test
    fun typedFailuresStayDistinctAndTheLegacyApiStillReturnsAnEmptyArray() = runBlocking {
        var status = 200
        var response = """{"success":true,"results":[]}"""
        var failTls = false
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            if (failTls) throw SSLHandshakeException("Fixture TLS failure")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("fixture").body(response.toResponseBody()).build()
        }.build()
        val wigle = WigleClient(WigleTransport(client))

        suspend fun checkResult(expected: WigleWifiLookupResult) {
            assertEquals(expected, wigle.fetchWifiDataResult(25.0, 105.0, "fixture-token"))
            assertEquals("[]", wigle.fetchWifiData(25.0, 105.0, "fixture-token"))
        }

        try {
            checkResult(WigleWifiLookupResult.Records("[]"))
            response = """{"success":true,"results":[{"netid":"02:00:00:00:00:03","trilat":25.0,"trilong":105.0,"ssid":"Fixture","frequency":2412}]}"""
            val populated = wigle.fetchWifiDataResult(25.0, 105.0, "fixture-token") as WigleWifiLookupResult.Records
            assertEquals(1, JSONArray(populated.json).length())
            assertEquals(populated.json, wigle.fetchWifiData(25.0, 105.0, "fixture-token"))

            status = 401
            checkResult(WigleWifiLookupResult.HttpFailure(401))
            status = 429
            checkResult(WigleWifiLookupResult.HttpFailure(429))
            status = 200
            response = "not JSON"
            checkResult(WigleWifiLookupResult.InvalidResponse)
            failTls = true
            checkResult(WigleWifiLookupResult.RequestFailed)
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }
}
