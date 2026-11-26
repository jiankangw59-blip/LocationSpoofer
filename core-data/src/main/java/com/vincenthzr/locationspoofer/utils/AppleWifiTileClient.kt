package com.vincenthzr.locationspoofer.utils

import com.vincenthzr.locationspoofer.data.model.RemoteWifiRegion
import com.vincenthzr.locationspoofer.data.model.RemoteWifiSnapshot
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class RemoteWifiQueryException(val httpStatus: Int) : IOException("HTTP $httpStatus")
class RemoteWifiResponseException(cause: Throwable) : IOException("Invalid Wi-Fi tile response", cause)
class RemoteWifiNoCoverageException : IOException("No Wi-Fi records within the queried tile and radius")
class RemoteWifiTimeoutException : IOException("Wi-Fi search exceeded its time budget")

/** Read-only lookup. Only BSSID and recorded coordinates are exposed to the preview. */
class AppleWifiTileClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    internal suspend fun seedTile(tile: Long, latitude: Double, longitude: Double, region: RemoteWifiRegion,
        radius: Double): List<com.vincenthzr.locationspoofer.data.model.RemoteWifiPoint> = try {
        val bytes = fetch(tileRequest(tile, region))
        try { AppleWifiTileCodec.decode(bytes, latitude, longitude, radius, 1000) }
        catch (error: IllegalArgumentException) { throw RemoteWifiResponseException(error) }
    } catch (_: RemoteWifiNoCoverageException) { emptyList() }

    suspend fun query(latitude: Double, longitude: Double, region: RemoteWifiRegion): RemoteWifiSnapshot {
        val tileKey = AppleWifiTileCodec.tileKey(latitude, longitude)
        return withTimeoutOrNull(25_000L) {
            for (candidate in AppleWifiTileCodec.searchTileKeys(latitude, longitude)) {
                try {
                    val bytes = fetch(tileRequest(candidate, region))
                    val points = try { AppleWifiTileCodec.decode(bytes, latitude, longitude) }
                        catch (error: IllegalArgumentException) { throw RemoteWifiResponseException(error) }
                    if (points.isNotEmpty()) return@withTimeoutOrNull RemoteWifiSnapshot(
                        latitude, longitude, region, tileKey, System.currentTimeMillis(), points)
                } catch (_: RemoteWifiNoCoverageException) {
                    // Only empty/404 tiles expand the search. Auth, network and protocol
                    // failures keep their error and do not trigger repeated requests.
                }
            }
            throw RemoteWifiNoCoverageException()
        } ?: throw RemoteWifiTimeoutException()
    }

    private fun tileRequest(tileKey: Long, region: RemoteWifiRegion): Request {
        val request = Request.Builder()
            .url("https://${region.host}/wifi_request_tile")
            .header("Accept", "*/*")
            .header("User-Agent", "geod/1 CFNetwork/1496.0.7 Darwin/23.5.0")
            .header("X-os-version", "17.5.21F79")
            .header("X-tilekey", tileKey.toString())
            .build()
        return request
    }

    private suspend fun fetch(request: Request): ByteArray = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val bytes = response.use {
                        if (it.code == 404) throw RemoteWifiNoCoverageException()
                        if (!it.isSuccessful) throw RemoteWifiQueryException(it.code)
                        val body = it.body ?: throw IOException("Empty HTTP response")
                        val output = ByteArrayOutputStream()
                        body.byteStream().use { input ->
                            val buffer = ByteArray(8192)
                            var count = input.read(buffer)
                            while (count != -1) {
                                if (output.size() + count > 2 * 1024 * 1024) {
                                    throw RemoteWifiResponseException(IOException("Response exceeds 2 MiB"))
                                }
                                output.write(buffer, 0, count)
                                count = input.read(buffer)
                            }
                        }
                        output.toByteArray()
                    }
                    if (continuation.isActive) continuation.resume(bytes)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }
}
