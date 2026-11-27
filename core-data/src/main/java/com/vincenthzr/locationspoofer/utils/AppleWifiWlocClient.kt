package com.vincenthzr.locationspoofer.utils

import com.vincenthzr.locationspoofer.data.model.MAX_REPORTED_WIFI_CHANNEL
import com.vincenthzr.locationspoofer.data.model.RemoteWifiPoint
import com.vincenthzr.locationspoofer.data.model.RemoteWifiRegion
import com.vincenthzr.locationspoofer.data.model.appleWloc24GhzFrequencyMHz
import com.vincenthzr.locationspoofer.utils.CoordinateUtils.LatLng
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object WifiBssid {
    fun normalize(value: String): String? {
        val raw = value.trim().lowercase(Locale.ROOT).replace('-', ':')
        val expanded = if (raw.matches(Regex("[0-9a-f]{12}"))) raw.chunked(2).joinToString(":") else raw
        if (!expanded.matches(Regex("[0-9a-f]{1,2}(:[0-9a-f]{1,2}){5}"))) return null
        val address = expanded.split(':').joinToString(":") { it.padStart(2, '0') }
        if (address == "00:00:00:00:00:00" || address == "ff:ff:ff:ff:ff:ff" || address.take(2).toInt(16) and 1 != 0) return null
        return address
    }
}

private fun RemoteWifiPoint.hasConsistentApplePosition(other: RemoteWifiPoint): Boolean =
    GeoMath.distance(LatLng(latitude, longitude), LatLng(other.latitude, other.longitude)) <=
        WIFI_METADATA_MAX_POSITION_DELTA_METERS

internal fun RemoteWifiPoint.withMissingAppleRadioFrom(other: RemoteWifiPoint?): RemoteWifiPoint {
    val channel = other?.appleWlocChannel ?: return this
    if (appleWlocChannel != null && appleWlocChannel != channel) return this
    val frequency = appleWlocDerivedFrequencyMHz ?: other.appleWlocDerivedFrequencyMHz
    if (appleWlocChannel == channel && appleWlocDerivedFrequencyMHz == frequency) return this
    // Callers provide the same canonical BSSID from this lookup.
    return if (hasConsistentApplePosition(other)) copy(
        appleWlocChannel = channel, appleWlocDerivedFrequencyMHz = frequency
    ) else this
}

/** Apple's reported AP positions and channels. The response provides neither freshness nor observed RSSI. */
internal object AppleWifiWlocCodec {
    class LookupRequest(val bytes: ByteArray, val seeds: Set<String>, val filters24GhzNeighbors: Boolean)

    private class Writer {
        private val output = ByteArrayOutputStream()
        private fun varint(value: Long) {
            var rest = value
            while (rest and -128L != 0L) { output.write((rest.toInt() and 127) or 128); rest = rest ushr 7 }
            output.write(rest.toInt())
        }
        fun number(field: Int, value: Long) { varint((field shl 3).toLong()); varint(value) }
        fun message(field: Int, value: ByteArray) { varint((field shl 3 or 2).toLong()); varint(value.size.toLong()); output.write(value) }
        fun bytes(): ByteArray = output.toByteArray()
    }

    fun request(bssids: List<String>, filter24GhzNeighbors: Boolean = false): LookupRequest {
        val valid = bssids.map { requireNotNull(WifiBssid.normalize(it)) }.toSet()
        require(valid.size in 1..6)
        val device = Writer().apply {
            message(1, "iPhone OS17.5/21F79".toByteArray()); message(2, "iPhone12,1".toByteArray())
        }.bytes()
        val payload = Writer().apply {
            valid.forEach { message(2, Writer().apply { message(1, it.toByteArray()) }.bytes()) }
            number(3, 0); number(4, 0); message(33, device)
            if (filter24GhzNeighbors) number(31, 1)
        }.bytes()
        require(payload.size < 256)
        val prefix = "0001000a656e2d3030315f3030310013636f6d2e6170706c652e6c6f636174696f6e64000c31372e352e312e323146393000000001000000"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return LookupRequest(prefix + byteArrayOf(payload.size.toByte()) + payload, valid, filter24GhzNeighbors)
    }

    fun decode(bytes: ByteArray, latitude: Double, longitude: Double, radiusMeters: Double,
        request: LookupRequest? = null): List<RemoteWifiPoint> {
        require(bytes.size in 10..2_097_152 && radiusMeters.isFinite() && radiusMeters in 0.0..50_000.0)
        require(latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0)
        val result = LinkedHashMap<String, RemoteWifiPoint>()
        val conflictedFrequencyBssids = mutableSetOf<String>()
        var responseContradictsBand = false
        val response = Reader(bytes.copyOfRange(10, bytes.size))
        while (response.more()) {
            val tag = response.number().toInt()
            if (tag != 18) { response.skip(tag and 7); continue }
            val device = Reader(response.bytes())
            var bssid: String? = null; var lat: Double? = null; var lon: Double? = null
            var channel: Int? = null
            var conflictingChannel = false
            var reportsOutside24Ghz = false
            while (device.more()) when (val field = device.number()) {
                10L -> bssid = WifiBssid.normalize(device.bytes().toString(Charsets.UTF_8))
                18L -> {
                    val position = Reader(device.bytes())
                    while (position.more()) when (val coord = position.number().toInt()) {
                        8 -> lat = position.number() * 1e-8
                        16 -> lon = position.number() * 1e-8
                        else -> position.skip(coord and 7)
                    }
                }
                // ALSWirelessAP.channel, field 21 with wire type 0.
                168L -> device.number().takeIf { it in 1L..MAX_REPORTED_WIFI_CHANNEL.toLong() }?.toInt()?.let { reported ->
                    if (channel != null && channel != reported) conflictingChannel = true
                    if (appleWloc24GhzFrequencyMHz(reported) == null) reportsOutside24Ghz = true
                    channel = reported
                }
                else -> device.skip((field and 7).toInt())
            }
            val address = bssid ?: continue
            // Apple can return the input seeds regardless of the requested neighbor band.
            val filteredNeighbor = request?.filters24GhzNeighbors == true && address !in request.seeds
            if (filteredNeighbor && reportsOutside24Ghz) responseContradictsBand = true
            val a = lat ?: continue; val b = lon ?: continue
            if (a !in -90.0..90.0 || b !in -180.0..180.0) continue
            val distance = GeoMath.distance(LatLng(latitude, longitude), LatLng(a, b))
            if (!(distance <= radiusMeters)) continue
            val frequency = if (filteredNeighbor) channel?.let(::appleWloc24GhzFrequencyMHz) else null
            val point = RemoteWifiPoint(address, a, b, distance, appleWlocChannel = channel,
                appleWlocDerivedFrequencyMHz = frequency)
            val first = result[address]
            if (request?.filters24GhzNeighbors == true) {
                val differs = first?.appleWlocChannel != null && channel != null && first.appleWlocChannel != channel
                if ((conflictingChannel || differs) && (first == null || first.hasConsistentApplePosition(point)))
                    conflictedFrequencyBssids += address
            }
            result[address] = first?.withMissingAppleRadioFrom(point) ?: point
        }
        return result.values.sortedBy { it.distanceMeters }.take(1000).map {
            if (it.appleWlocDerivedFrequencyMHz != null && (responseContradictsBand || it.bssid in conflictedFrequencyBssids))
                it.copy(appleWlocDerivedFrequencyMHz = null)
            else it
        }
    }

    private class Reader(private val input: ByteArray) {
        private var offset = 0
        fun more() = offset < input.size
        fun number(): Long {
            var result = 0L
            for (index in 0..9) {
                require(more()) { "Truncated protobuf" }
                val byte = input[offset++].toInt() and 255
                require(index != 9 || byte <= 1) { "Overflowed protobuf" }
                result = result or ((byte and 127).toLong() shl (index * 7))
                if (byte and 128 == 0) return result
            }
            error("Invalid protobuf")
        }
        fun bytes(): ByteArray {
            val size = number(); require(size in 0L..(input.size - offset).toLong())
            return input.copyOfRange(offset, offset + size.toInt()).also { offset += size.toInt() }
        }
        fun skip(wire: Int) {
            when (wire) { 0 -> number(); 1 -> offset += 8; 2 -> bytes(); 5 -> offset += 4; else -> error("Invalid wire") }
            require(offset <= input.size)
        }
    }
}

class AppleWifiWlocClient(private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS)
    .readTimeout(8, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build()) {
    suspend fun query(bssids: List<String>, latitude: Double, longitude: Double, region: RemoteWifiRegion,
        radiusMeters: Double = 12_000.0): List<RemoteWifiPoint> {
        val host = if (region == RemoteWifiRegion.MAINLAND_CHINA) "gs-loc-cn.apple.com" else "gs-loc.apple.com"
        val lookup = AppleWifiWlocCodec.request(bssids, filter24GhzNeighbors = region == RemoteWifiRegion.MAINLAND_CHINA)
        val request = Request.Builder().url("https://$host/clls/wloc")
            .header("User-Agent", "locationd/2890.16.16 CFNetwork/1496.0.7 Darwin/23.5.0")
            .header("Accept", "*/*").header("Accept-Charset", "utf-8").header("Accept-Language", "en-us")
            .post(lookup.bytes.toRequestBody("application/x-www-form-urlencoded".toMediaType())).build()
        val bytes = suspendCancellableCoroutine<ByteArray> { continuation ->
            val call = client.newCall(request); continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(IOException("WLOC transport failed")) }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val data = response.use {
                            if (!it.isSuccessful) throw RemoteWifiQueryException(it.code)
                            val body = it.body ?: throw IOException("Missing WLOC response")
                            val output = ByteArrayOutputStream()
                            body.byteStream().use { input ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val size = input.read(buffer); if (size < 0) break
                                    if (output.size() + size > 2_097_152) throw RemoteWifiResponseException(IOException("WLOC response exceeds 2 MiB"))
                                    output.write(buffer, 0, size)
                                }
                            }
                            output.toByteArray()
                        }
                        if (continuation.isActive) continuation.resume(data)
                    } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
                }
            })
        }
        return try { AppleWifiWlocCodec.decode(bytes, latitude, longitude, radiusMeters, lookup) }
        catch (error: IllegalArgumentException) { throw RemoteWifiResponseException(error) }
        catch (error: IllegalStateException) { throw RemoteWifiResponseException(error) }
    }
}
