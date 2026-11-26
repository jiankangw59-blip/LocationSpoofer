package com.vincenthzr.locationspoofer

import android.os.Bundle
import android.os.SystemClock
import com.vincenthzr.locationspoofer.utils.CoordinateUtils.LatLng
import com.vincenthzr.locationspoofer.utils.GeoMath
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** Diagnostic only: reuse already-published remote BSSIDs, never the physical phone scan. */
internal fun SystemFilesInstrumentation.diagnoseAppleWifiWloc(control: Boolean = false, additionalCandidates: Boolean = false): Bundle {
    val output = Bundle()
    val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
    try {
        val transport = targetContext.getSharedPreferences("framework_config_state", 0)
        val original = checkNotNull(transport.getString("desired_config", null))
        val config = JSONObject(original)
        val source = when {
            additionalCandidates -> JSONObject(java.io.File(targetContext.cacheDir, "wigle-current-seed-candidates.json").readText())
            control -> JSONObject(java.io.File(targetContext.filesDir, "remote_wifi_preview.json").readText())
            else -> config
        }
        val target = if (control || additionalCandidates) LatLng(source.getDouble("latitude"), source.getDouble("longitude"))
            else LatLng(config.getDouble("wgs84_lat"), config.getDouble("wgs84_lng"))
        val wifi = if (control || additionalCandidates) source.getJSONArray("points") else config.getJSONObject("wifi_json").getJSONArray("nearbyWifi")
        val bssids = (0 until wifi.length()).map { wifi.getJSONObject(it).getString("bssid").lowercase() }
            .filter { it.matches(Regex("[0-9a-f]{2}(:[0-9a-f]{2}){5}")) }.distinct().take(6)
        check(bssids.isNotEmpty())
        val device = proto { message(1, "iPhone OS17.5/21F79".toByteArray()); message(2, "iPhone12,1".toByteArray()) }
        val payload = proto {
            bssids.forEach { bssid -> message(2, proto { message(1, bssid.toByteArray()) }) }
            number(3, 0); number(4, 0); message(33, device)
        }
        check(payload.size < 256)
        val prefix = "0001000a656e2d3030315f3030310013636f6d2e6170706c652e6c6f636174696f6e64000c31372e352e312e323146393000000001000000"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val host = if (target.lat in 18.0..54.0 && target.lng in 73.0..135.0) "gs-loc-cn.apple.com" else "gs-loc.apple.com"
        val request = Request.Builder().url("https://$host/clls/wloc")
            .header("User-Agent", "locationd/2890.16.16 CFNetwork/1496.0.7 Darwin/23.5.0")
            .header("Accept", "*/*").header("Accept-Charset", "utf-8").header("Accept-Language", "en-us")
            .post((prefix + byteArrayOf(payload.size.toByte()) + payload)
                .toRequestBody("application/x-www-form-urlencoded".toMediaType())).build()
        val begin = SystemClock.elapsedRealtime()
        val report = JSONObject().put("seed_count", bssids.size).put("host", host).put("control", control)
            .put("additional_candidates", additionalCandidates)
        client.newCall(request).execute().use { response ->
            val bytes = response.peekBody(2L * 1024 * 1024).bytes()
            report.put("http", response.code).put("bytes", bytes.size)
            if (response.isSuccessful) {
                check(bytes.size >= 10)
                val records = DiagnosticProto(bytes.copyOfRange(10, bytes.size)).messages(2)
                var unknownPositions = 0; var missingPositions = 0
                val distances = records.mapNotNull { record ->
                    DiagnosticProto(record).messages(2).firstOrNull()?.let { location ->
                        val fields = DiagnosticProto(location).numbers()
                        val lat = fields[1]?.times(1e-8) ?: return@let null
                        val lng = fields[2]?.times(1e-8) ?: return@let null
                        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) { unknownPositions++; null }
                        else GeoMath.distance(target, LatLng(lat, lng))
                    } ?: run { missingPositions++; null }
                }
                report.put("wifi_records", records.size).put("valid_positions", distances.size)
                    .put("within_2km", distances.count { it <= 2000 }).put("unknown_positions", unknownPositions)
                    .put("unusable_positions", missingPositions)
                distances.minOrNull()?.let { report.put("nearest_meters", it) }
            }
        }
        report.put("milliseconds", SystemClock.elapsedRealtime() - begin)
            .put("desired_config_unchanged", original == transport.getString("desired_config", null))
        output.putString("diagnosis", report.toString()); output.putString("result", "PASS")
    } catch (error: Exception) { output.putString("result", "FAIL: ${error.javaClass.simpleName}") }
    finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    return output
}

private class DiagnosticWriter {
    val out = ByteArrayOutputStream()
    private fun varint(value: Long) {
        var remaining = value
        while (remaining and -128L != 0L) { out.write((remaining.toInt() and 127) or 128); remaining = remaining ushr 7 }
        out.write(remaining.toInt())
    }
    fun number(field: Int, value: Long) { varint((field shl 3).toLong()); varint(value) }
    fun message(field: Int, value: ByteArray) { varint((field shl 3 or 2).toLong()); varint(value.size.toLong()); out.write(value) }
}
private fun proto(block: DiagnosticWriter.() -> Unit) = DiagnosticWriter().apply(block).out.toByteArray()

private class DiagnosticProto(private val bytes: ByteArray) {
    private var offset = 0
    private fun number(): Long {
        var value = 0L
        for (index in 0..9) {
            check(offset < bytes.size)
            val byte = bytes[offset++].toInt() and 255
            check(index != 9 || byte <= 1)
            value = value or ((byte and 127).toLong() shl (index * 7))
            if (byte and 128 == 0) return value
        }
        error("varint overflow")
    }
    private fun message(): ByteArray {
        val size = number(); check(size in 0L..(bytes.size - offset).toLong())
        return bytes.copyOfRange(offset, offset + size.toInt()).also { offset += size.toInt() }
    }
    private fun skip(wire: Int) {
        when (wire) { 0 -> number(); 1 -> offset += 8; 2 -> message(); 5 -> offset += 4; else -> error("invalid wire") }
        check(offset <= bytes.size)
    }
    fun messages(field: Int): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        while (offset < bytes.size) {
            val tag = number().toInt()
            if (tag == (field shl 3 or 2)) result.add(message()) else skip(tag and 7)
        }
        return result
    }
    fun numbers(): Map<Int, Long> {
        val result = mutableMapOf<Int, Long>()
        while (offset < bytes.size) {
            val tag = number().toInt()
            if (tag and 7 == 0) result[tag ushr 3] = number() else skip(tag and 7)
        }
        return result
    }
}
