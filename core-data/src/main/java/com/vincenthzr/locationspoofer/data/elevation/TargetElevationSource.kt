package com.vincenthzr.locationspoofer.data.elevation

import android.annotation.TargetApi
import android.content.Context
import android.location.Location
import android.location.altitude.AltitudeConverter
import android.os.Build
import kotlin.coroutines.resume
import kotlin.math.round
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Estimated *surface* (including buildings/vegetation) from a DEM, never a handset or floor measurement. */
data class TargetElevation(val surfaceMslMeters: Double, val ellipsoidMeters: Double)

/** Open-Meteo Copernicus DEM GLO-90 (EGM2008). See assets/target-elevation-source.txt. */
object TargetElevationSource {
    private const val MAX_BYTES = 4096L
    private val http by lazy {
        OkHttpClient.Builder().callTimeout(6, TimeUnit.SECONDS)
            .connectTimeout(3, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    }

    /** Same canonical point for network, geoid calculation and the caller's cache. No identifiers are sent. */
    fun key(latitudeWgs84: Double, longitudeWgs84: Double): String? {
        if (latitudeWgs84 !in -90.0..90.0 || longitudeWgs84 !in -180.0..180.0) return null
        val lat = round(latitudeWgs84 * 10_000).toInt()
        var lon = round(longitudeWgs84 * 10_000).toInt()
        if (lon == 1_800_000) lon = -1_800_000
        return String.format(Locale.US, "%.4f,%.4f", lat / 10_000.0, lon / 10_000.0)
    }

    /** No location permission, current position, user DB, or fallback to yesterday's altitude. */
    suspend fun lookup(context: Context, latitudeWgs84: Double, longitudeWgs84: Double): TargetElevation? {
        if (Build.VERSION.SDK_INT < 34) return null
        val point = key(latitudeWgs84, longitudeWgs84) ?: return null
        return withTimeoutOrNull(7_000L) {
            // Skip the remote request when the device cannot convert this point's vertical datum.
            val zeroMsl = withContext(Dispatchers.IO) {
                Api34.zeroMsl(context, point.substringBefore(',').toDouble(), point.substringAfter(',').toDouble())
            } ?: return@withTimeoutOrNull null
            if (zeroMsl !in -200.0..200.0) return@withTimeoutOrNull null
            val surface = readSurface(point) ?: return@withTimeoutOrNull null
            convert(surface, zeroMsl)
        }
    }

    internal fun convert(surfaceMsl: Double, zeroMsl: Double): TargetElevation? {
        if (surfaceMsl !in -600.0..9_000.0 || zeroMsl !in -200.0..200.0) return null
        // H = h - N. At h=0 the platform reports H0=-N, so h_target=H_target-H0.
        return TargetElevation(surfaceMsl, surfaceMsl - zeroMsl)
    }

    internal fun parseSurface(json: String): Double? {
        val obj = try { Json.parseToJsonElement(json) as? JsonObject }
        catch (_: IllegalArgumentException) { null } ?: return null
        if ("error" in obj) return null
        val one = (obj["elevation"] as? JsonArray)?.singleOrNull() as? JsonPrimitive ?: return null
        return one.takeUnless { it.isString }?.doubleOrNull?.takeIf { it in -600.0..9_000.0 }
    }

    private suspend fun readSurface(point: String): Double? = suspendCancellableCoroutine { cont ->
        val url = "https://api.open-meteo.com/v1/elevation?latitude=${point.substringBefore(',')}&longitude=${point.substringAfter(',')}"
        val call = http.newCall(Request.Builder().url(url).header("Accept", "application/json").build())
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cont.resume(null)
            override fun onResponse(call: Call, response: Response) {
                val value = try {
                    response.use {
                        val body = it.body ?: return@use null
                        if (it.code != 200 || body.contentLength() > MAX_BYTES) return@use null
                        val source = body.source()
                        if (source.request(MAX_BYTES + 1)) null else parseSurface(source.readUtf8())
                    }
                } catch (_: IOException) { null }
                cont.resume(value)
            }
        })
    }

    @TargetApi(34)
    private object Api34 {
        fun zeroMsl(context: Context, lat: Double, lon: Double): Double? {
            val location = Location("terrain-datum").also { it.latitude = lat; it.longitude = lon; it.altitude = 0.0 }
            return try {
                AltitudeConverter().addMslAltitudeToLocation(context, location)
                if (location.hasMslAltitude()) location.mslAltitudeMeters else null
            } catch (_: IOException) { null }
            catch (_: IllegalArgumentException) { null }
        }
    }
}
