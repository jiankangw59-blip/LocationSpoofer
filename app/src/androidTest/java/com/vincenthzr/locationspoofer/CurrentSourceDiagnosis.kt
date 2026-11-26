package com.vincenthzr.locationspoofer

import android.os.Bundle
import android.os.SystemClock
import com.vincenthzr.locationspoofer.data.db.EnvironmentDao
import com.vincenthzr.locationspoofer.data.model.RemoteWifiRegion
import com.vincenthzr.locationspoofer.data.repository.*
import com.vincenthzr.locationspoofer.utils.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Reads the active target; uses separate query caches and never imports or publishes output. */
internal fun SystemFilesInstrumentation.diagnoseCurrentSources(neighbors: Boolean = false): Bundle = runBlocking {
    val output = Bundle()
    val files = Collections.synchronizedList(mutableListOf<File>())
    var stage = "read_config"
    val trace = Collections.synchronizedList(mutableListOf<JSONObject>())
    val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS).addInterceptor { chain ->
            val request = chain.request(); val begin = SystemClock.elapsedRealtime()
            val item = JSONObject().put("host", request.url.host).put("path", request.url.encodedPath)
            try {
                chain.proceed(request).also { response ->
                    item.put("http", response.code)
                    request.header("X-tilekey")?.let { item.put("tile", it) }
                    if (request.url.host == "opencellid.org") runCatching {
                        val body = JSONObject(response.peekBody(32768).string())
                        item.put("json_keys", JSONArray(body.keys().asSequence().toList()))
                        item.put("cells_count", body.optJSONArray("cells")?.length() ?: -1)
                        val rows = body.optJSONArray("cells")
                        if (rows != null && rows.length() > 0) item.put("cell_field_names", JSONArray(rows.getJSONObject(0).keys().asSequence().toList()))
                        val code = body.opt("code")
                        if (code is Number || code?.toString()?.matches(Regex("[A-Z0-9_-]{1,32}")) == true) item.put("api_code", code)
                    }
                }
            } catch (error: Exception) { item.put("error", error.javaClass.simpleName); throw error }
            finally { item.put("milliseconds", SystemClock.elapsedRealtime() - begin); trace.add(item) }
        }.build()
    try {
        val transport = targetContext.getSharedPreferences("framework_config_state", android.content.Context.MODE_PRIVATE)
        val original = checkNotNull(transport.getString("desired_config", null))
        val config = JSONObject(original)
        val lat = config.getDouble("wgs84_lat"); val lng = config.getDouble("wgs84_lng")
        val region = if (lat in 18.0..54.0 && lng in 73.0..135.0) RemoteWifiRegion.MAINLAND_CHINA else RemoteWifiRegion.GLOBAL
        stage = "wait_di"
        val koin = withTimeout(20_000) {
            while (GlobalContext.getOrNull() == null) delay(100)
            GlobalContext.get()
        }
        stage = "query"
        fun cache(kind: String) = File(targetContext.cacheDir, "current-source-$kind-${UUID.randomUUID()}.json").also { files.add(it) }
        fun failure(error: Exception) = JSONObject().put("ok", false).put("error", error.javaClass.simpleName).apply {
            if (error is RemoteWifiQueryException) put("http", error.httpStatus)
        }
        val wifi = async(Dispatchers.IO) {
            try {
                if (neighbors) {
                    val type = Class.forName("com.vincenthzr.locationspoofer.utils.AppleWifiTileCodec")
                    val codec = type.getField("INSTANCE").get(null)
                    val key = type.getDeclaredMethod("tileKey", Double::class.javaPrimitiveType, Double::class.javaPrimitiveType).invoke(codec, lat, lng) as Long
                    var x = 0; var y = 0
                    repeat(13) { bit -> x = x or (((key ushr (bit * 2)) and 1).toInt() shl bit); y = y or (((key ushr (bit * 2 + 1)) and 1).toInt() shl bit) }
                    val decode = type.getDeclaredMethod("decode", ByteArray::class.java, Double::class.javaPrimitiveType, Double::class.javaPrimitiveType)
                    var found: JSONObject? = null
                    for ((dx, dy) in listOf(0 to 0, -1 to 0, 1 to 0, 0 to -1, 0 to 1, -1 to -1, 1 to -1, -1 to 1, 1 to 1)) {
                        if (y + dy !in 0..8191) continue
                        var cx = (x + dx + 8192) % 8192; var cy = y + dy; var candidate = 1L shl 26
                        repeat(13) { bit -> candidate = candidate or ((cx and 1).toLong() shl (bit * 2)) or ((cy and 1).toLong() shl (bit * 2 + 1)); cx = cx ushr 1; cy = cy ushr 1 }
                        val request = okhttp3.Request.Builder().url("https://${region.host}/wifi_request_tile")
                            .header("Accept", "*/*").header("User-Agent", "geod/1 CFNetwork/1496.0.7 Darwin/23.5.0")
                            .header("X-os-version", "17.5.21F79").header("X-tilekey", candidate.toString()).build()
                        val count = client.newCall(request).execute().use { response ->
                            if (response.code == 404) 0 else {
                                if (!response.isSuccessful) throw RemoteWifiQueryException(response.code)
                                (decode.invoke(codec, checkNotNull(response.body).bytes(), lat, lng) as List<*>).size
                            }
                        }
                        if (count > 0) { found = JSONObject().put("ok", true).put("count", count).put("tile", candidate); break }
                    }
                    found ?: JSONObject().put("ok", false).put("error", "RemoteWifiNoCoverageException")
                } else {
                    val value = withTimeout(25_000) { RemoteWifiRepository(AppleWifiTileClient(client), cache("wifi")).query(lat, lng, region) }
                    JSONObject().put("ok", true).put("count", value.points.size).put("tile", value.tileKey)
                }
            } catch (error: Exception) { failure(error) }
        }
        val cells = async(Dispatchers.IO) {
            try {
                val value = withTimeout(90_000) { RemoteCellRepository(AppleCellClient(client), koin.get<EnvironmentDao>(),
                    koin.get<SettingsRepository>(), cache("cell")).query(lat, lng, region) }
                JSONObject().put("ok", true).put("count", value.points.size).put("radio", value.seed.radio.name)
            } catch (error: Exception) { failure(error) }
        }
        val result = JSONObject().put("latitude", lat).put("longitude", lng).put("wifi", wifi.await()).put("cells", cells.await())
            .put("requests", JSONArray(trace.toList()))
            .put("desired_config_unchanged", transport.getString("desired_config", null) == original)
        output.putString("diagnosis", result.toString())
        output.putString("result", "PASS")
    } catch (error: Exception) { output.putString("result", "FAIL: $stage/${error.javaClass.simpleName}") }
    finally {
        files.forEach { file -> file.delete(); File(file.path + ".bak").delete() }
        client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
    }
    output
}
