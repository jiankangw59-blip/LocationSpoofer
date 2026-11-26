package com.vincenthzr.locationspoofer.utils

import com.vincenthzr.locationspoofer.data.model.*
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class RemoteCellNoSeedException : IOException("No valid nearby LTE/NR seed")
class RemoteCellNoCoverageException : IOException("No nearby cells in WLOC response")

class AppleCellClient(private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build(),
    private val openCells: OpenCellSearchClient = OpenCellSearchClient(client),
    private val offlineSeeds: OfflineCellSeedIndex? = null) {

    suspend fun query(latitude: Double, longitude: Double, region: RemoteWifiRegion, seed: RemoteCellIdentity): RemoteCellSnapshot {
        val host = if (region == RemoteWifiRegion.MAINLAND_CHINA) "gs-loc-cn.apple.com" else "gs-loc.apple.com"
        val request = Request.Builder().url("https://$host/clls/wloc")
            .header("User-Agent", "locationd/2890.16.16 CFNetwork/1496.0.7 Darwin/23.5.0")
            .header("Accept", "*/*").header("Accept-Charset", "utf-8").header("Accept-Language", "en-us")
            .post(AppleCellCodec.request(seed).toRequestBody("application/x-www-form-urlencoded".toMediaType())).build()
        val points = try {
            AppleCellCodec.decode(fetch(request), latitude, longitude, seed, maxDistanceMeters = 10_000.0)
        } catch (error: IllegalArgumentException) {
            throw RemoteWifiResponseException(error)
        }
        if (points.isEmpty()) throw RemoteCellNoCoverageException()
        return RemoteCellSnapshot(latitude, longitude, region, seed, System.currentTimeMillis(), points)
    }

    /** Shared finite grid/pagination/age discovery; never picks a known stale or invented identity. */
    suspend fun findSeed(latitude: Double, longitude: Double, token: String, preferredRadio: RemoteCellRadio? = null,
        excluded: Set<RemoteCellIdentity> = emptySet()): RemoteCellIdentity? {
        try {
            val indexed = offlineSeeds?.search(latitude, longitude, preferredRadio)
            if (indexed != null && indexed.candidates.isNotEmpty()) {
                // Exhausting a known local candidate pool must not repeat a 29-box online scan.
                return indexed.candidates.firstOrNull { it.identity !in excluded }?.identity
            }
        } catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (_: Exception) { android.util.Log.w("LocationSpoofer", "Offline cell index unavailable; using configured online source") }
        if (token.isBlank()) return null
        val result = openCells.search(latitude, longitude, token, preferredRadio)
        result.records.asSequence().mapNotNull { it.appleSeed() }.firstOrNull { it !in excluded }?.let { return it }
        if (!result.report.complete) throw OpenCellSearchIncompleteException()
        return null
    }

    private suspend fun fetch(request: Request): ByteArray = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val bytes = response.use {
                        if (!it.isSuccessful) throw RemoteWifiQueryException(it.code)
                        val body = it.body ?: throw IOException("Empty response")
                        val output = ByteArrayOutputStream()
                        body.byteStream().use { input ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (output.size() + count > 2_097_152) throw IOException("Response too large")
                                output.write(buffer, 0, count)
                            }
                        }
                        output.toByteArray()
                    }
                    if (continuation.isActive) continuation.resume(bytes)
                } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
            }
        })
    }
}
