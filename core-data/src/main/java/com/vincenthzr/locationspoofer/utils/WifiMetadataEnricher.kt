package com.vincenthzr.locationspoofer.utils

import com.vincenthzr.locationspoofer.data.model.*
import com.vincenthzr.locationspoofer.utils.CoordinateUtils.LatLng
import kotlinx.coroutines.CancellationException
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** One bounded area request per lookup, never one request per BSSID. No credential is written to a cache or error message. */
class WifiMetadataEnricher(
    private val tokenProvider: () -> String,
    client: OkHttpClient = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build(),
    private val clock: () -> Long = System::currentTimeMillis,
    proxyProvider: RequestProxyLeaseProvider? = null,
    private val transport: WigleTransport = WigleTransport(client, proxyProvider, clock)
) {
    private var rejectedFingerprint: String? = null
    private var retryAfter = 0L
    private var rejectedStatus: Int? = null

    suspend fun enrich(snapshot: RemoteWifiSnapshot, cached: RemoteWifiSnapshot? = null): RemoteWifiSnapshot {
        val now = clock()
        val prior = cached?.takeIf {
            GeoMath.distance(LatLng(snapshot.latitude, snapshot.longitude), LatLng(it.latitude, it.longitude)) <= 2.0
        }?.points?.associateBy { it.bssid }.orEmpty()
        val points = snapshot.points.map { point ->
            val metadata = point.metadata?.takeIf { now - it.fetchedAt in 0..86_400_000L }
                ?: prior[point.bssid]?.metadata?.takeIf { now - it.fetchedAt in 0..86_400_000L }
            point.copy(metadata = metadata)
        }
        val base = snapshot.copy(points = points)
        val token = tokenProvider().trim()
        if (token.isEmpty()) return base.copy(metadataStatus = RemoteWifiMetadataStatus.NOT_CONFIGURED)
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        if (fingerprint == rejectedFingerprint && now < retryAfter) {
            return base.copy(metadataStatus = RemoteWifiMetadataStatus.UNAVAILABLE, metadataHttpStatus = rejectedStatus)
        }
        try {
            val latMin = snapshot.points.minOf { it.latitude }
            val latMax = snapshot.points.maxOf { it.latitude }
            val lngMin = snapshot.points.minOf { it.longitude }
            val lngMax = snapshot.points.maxOf { it.longitude }
            require(lngMax - lngMin < 1.0 && latMax - latMin < 1.0)
            val url = "https://api.wigle.net/api/v2/network/search".toHttpUrl().newBuilder()
                .addQueryParameter("latrange1", (latMin - 0.00001).coerceAtLeast(-90.0).toString())
                .addQueryParameter("latrange2", (latMax + 0.00001).coerceAtMost(90.0).toString())
                .addQueryParameter("longrange1", (lngMin - 0.00001).coerceAtLeast(-180.0).toString())
                .addQueryParameter("longrange2", (lngMax + 0.00001).coerceAtMost(180.0).toString())
                .addQueryParameter("resultsPerPage", "100").build()
            val request = Request.Builder().url(url).header("Authorization", "Basic $token")
                .header("Accept", "application/json").build()
            val metadata = WifiMetadataCodec.decode(JSONObject(transport.fetch(request).toString(Charsets.UTF_8)), snapshot, now)
            rejectedFingerprint = null; rejectedStatus = null; retryAfter = 0L
            return base.copy(points = points.map { point -> point.copy(metadata = metadata[point.bssid] ?: point.metadata) },
                metadataStatus = if (metadata.isEmpty()) RemoteWifiMetadataStatus.NO_MATCH else RemoteWifiMetadataStatus.MATCHED)
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            val status = (error as? RemoteWifiQueryException)?.httpStatus
            if (status in listOf(401, 403, 429)) {
                rejectedFingerprint = fingerprint; rejectedStatus = status
                retryAfter = now + if (status == 429) 15 * 60_000L else 60_000L
            }
            return base.copy(metadataStatus = RemoteWifiMetadataStatus.UNAVAILABLE, metadataHttpStatus = status)
        }
    }

}
