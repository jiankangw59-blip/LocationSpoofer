package com.vincenthzr.locationspoofer

import android.os.Bundle
import android.os.SystemClock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Same-UID transport/encoding controls; never imports data or changes active output. */
internal fun SystemFilesInstrumentation.diagnoseAppleTileMatrix(spiral: Boolean = false): Bundle {
    val output = Bundle()
    val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
    try {
        val transport = targetContext.getSharedPreferences("framework_config_state", 0)
        val original = checkNotNull(transport.getString("desired_config", null))
        val config = JSONObject(original)
        val type = Class.forName("com.vincenthzr.locationspoofer.utils.AppleWifiTileCodec")
        val codec = type.getField("INSTANCE").get(null)
        val tileKey = type.getDeclaredMethod("tileKey", Double::class.javaPrimitiveType, Double::class.javaPrimitiveType)
        val decode = type.getDeclaredMethod("decode", ByteArray::class.java, Double::class.javaPrimitiveType, Double::class.javaPrimitiveType)
        val results = JSONArray()
        val targets = if (spiral) listOf(Triple("current_spiral", config.getDouble("wgs84_lat"), config.getDouble("wgs84_lng"))) else listOf(
            Triple("current", config.getDouble("wgs84_lat"), config.getDouble("wgs84_lng")),
            Triple("previous_success", 30.6544979, 113.1366294)
        )
        for ((label, latitude, longitude) in targets) {
            val key = tileKey.invoke(codec, latitude, longitude) as Long
            val keys = if (spiral) diagnosticSpiralKeys(key) else listOf(key)
            val hosts = if (spiral) listOf("gspe85-cn-ssl.ls.apple.com") else listOf("gspe85-cn-ssl.ls.apple.com", "gspe85-ssl.ls.apple.com")
            for (candidate in keys) for (host in hosts) {
                val begin = SystemClock.elapsedRealtime()
                val item = JSONObject().put("target", label).put("tile", candidate).put("host", host)
                try {
                    val request = Request.Builder().url("https://$host/wifi_request_tile")
                        .header("Accept", "*/*")
                        .header("User-Agent", "geod/1 CFNetwork/1496.0.7 Darwin/23.5.0")
                        .header("X-os-version", "17.5.21F79").header("X-tilekey", candidate.toString()).build()
                    client.newCall(request).execute().use { response ->
                        val bytes = response.peekBody(2L * 1024 * 1024).bytes()
                        item.put("http", response.code).put("protocol", response.protocol.toString())
                            .put("body_bytes", bytes.size)
                            .put("body_sha256", MessageDigest.getInstance("SHA-256").digest(bytes)
                                .joinToString("") { "%02x".format(it.toInt() and 255) })
                        val headers = JSONObject()
                        for (name in listOf("server", "content-type", "content-length", "cache-control", "age", "via", "x-cache")) {
                            response.header(name)?.let { headers.put(name, it) }
                        }
                        item.put("headers", headers)
                        if (response.isSuccessful) {
                            item.put("decoded_within_2km", (decode.invoke(codec, bytes, latitude, longitude) as List<*>).size)
                        } else if (bytes.size <= 4096 && bytes.all { it.toInt() in 9..126 }) {
                            item.put("body_excerpt", bytes.toString(Charsets.US_ASCII).take(160))
                        }
                    }
                } catch (error: Exception) { item.put("error", error.javaClass.simpleName) }
                finally { item.put("milliseconds", SystemClock.elapsedRealtime() - begin); results.put(item) }
            }
        }
        output.putString("diagnosis", JSONObject().put("requests", results)
            .put("desired_config_unchanged", original == transport.getString("desired_config", null)).toString())
        output.putString("result", "PASS")
    } catch (error: Exception) { output.putString("result", "FAIL: ${error.javaClass.simpleName}") }
    finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    return output
}

/** Two complete surrounding rings, independently encoded; never scans without a bound. */
private fun diagnosticSpiralKeys(center: Long): List<Long> {
    var cx = 0; var cy = 0
    repeat(13) { bit ->
        cx = cx or (((center ushr (2 * bit)) and 1).toInt() shl bit)
        cy = cy or (((center ushr (2 * bit + 1)) and 1).toInt() shl bit)
    }
    var x = 0; var y = 0; var dx = 0; var dy = -1
    return (0 until 25).mapNotNull {
        val row = cy + y
        var col = (cx + x + 8192) % 8192
        var candidateRow = row; var key = 1L shl 26
        repeat(13) { bit ->
            key = key or ((col and 1).toLong() shl (2 * bit)) or ((candidateRow and 1).toLong() shl (2 * bit + 1))
            col = col ushr 1; candidateRow = candidateRow ushr 1
        }
        if (x == y || x < 0 && x == -y || x > 0 && x == 1 - y) { val next = -dy; dy = dx; dx = next }
        x += dx; y += dy
        key.takeIf { row in 0..8191 }
    }.distinct()
}
