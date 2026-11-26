package com.vincenthzr.locationspoofer

import android.os.Bundle
import com.vincenthzr.locationspoofer.data.repository.SettingsRepository
import com.vincenthzr.locationspoofer.utils.FeedMeProxyLeaseProvider
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.koin.core.context.GlobalContext
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit

/** Verify origin identity and authentication behavior before trusting unusually shaped source data. */
internal fun SystemFilesInstrumentation.diagnoseWigleNetworkIntegrity(): Bundle = runBlocking {
    val output = Bundle()
    var http: OkHttpClient? = null
    var lease: com.vincenthzr.locationspoofer.utils.RequestProxyLease? = null
    val requests = JSONArray()
    try {
        val prefs = targetContext.getSharedPreferences("framework_config_state", 0)
        val original = checkNotNull(prefs.getString("desired_config", null))
        val config = JSONObject(original)
        val lat = config.getDouble("wgs84_lat"); val lng = config.getDouble("wgs84_lng")
        val koin = withTimeout(20_000) {
            while (GlobalContext.getOrNull()?.getOrNull<FeedMeProxyLeaseProvider>() == null) delay(100)
            GlobalContext.get()
        }
        val token = koin.get<SettingsRepository>().getWigleApiToken().trim()
        val provider = koin.get<FeedMeProxyLeaseProvider>()
        val before = provider.status()
        lease = withContext(NonCancellable) { provider.acquire() }
        checkNotNull(lease)
        http = OkHttpClient.Builder().proxy(lease.proxy).connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
        for ((label, validAuth, pageSize) in listOf(Triple("valid_repeat", true, 100),
            Triple("valid_page_size_one", true, 1), Triple("invalid_auth_control", false, 1))) {
            val url = "https://api.wigle.net/api/v2/network/search".toHttpUrl().newBuilder()
                .addQueryParameter("latrange1", (lat - 0.002).toString()).addQueryParameter("latrange2", (lat + 0.002).toString())
                .addQueryParameter("longrange1", (lng - 0.002).toString()).addQueryParameter("longrange2", (lng + 0.002).toString())
                .addQueryParameter("resultsPerPage", pageSize.toString()).build()
            val auth = if (validAuth) token else java.util.Base64.getEncoder()
                .encodeToString("diagnostic-invalid:deliberately-invalid".toByteArray())
            val request = Request.Builder().url(url).header("Authorization", "Basic $auth")
                .header("Accept", "application/json").build()
            val entry = JSONObject().put("query", label).put("requested_page_size", pageSize)
            try {
                withContext(Dispatchers.IO) {
                    http.newCall(request).execute().use { response ->
                        val bytes = response.peekBody(2L * 1024 * 1024).bytes()
                        fun sha(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data)
                            .joinToString("") { "%02x".format(it.toInt() and 255) }
                        entry.put("http", response.code).put("protocol", response.protocol.toString())
                            .put("body_bytes", bytes.size).put("body_sha256", sha(bytes))
                            .put("handshake_present", response.handshake != null)
                        response.handshake?.let { handshake ->
                            entry.put("tls", handshake.tlsVersion.javaName)
                            val peer = handshake.peerCertificates.firstOrNull() as? X509Certificate
                            peer?.let { entry.put("certificate_subject", it.subjectX500Principal.name)
                                .put("certificate_issuer", it.issuerX500Principal.name).put("certificate_sha256", sha(it.encoded)) }
                        }
                        val headers = JSONObject()
                        for (name in listOf("server", "content-type", "content-length", "cache-control", "age"))
                            response.header(name)?.let { headers.put(name, it) }
                        entry.put("headers", headers)
                        val json = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrNull()
                        json?.let {
                            entry.put("success", it.optBoolean("success")).put("total_results", it.optLong("totalResults", -1))
                                .put("result_count", it.optInt("resultCount", -1)).put("first", it.optInt("first", -1))
                                .put("last", it.optInt("last", -1))
                            val records = it.optJSONArray("results") ?: JSONArray()
                            entry.put("raw_records", records.length())
                            if (records.length() > 0) entry.put("record_fields", JSONArray(records.getJSONObject(0).keys().asSequence().toList()))
                            val sample = JSONArray()
                            for (index in 0 until minOf(records.length(), 6)) {
                                val row = records.getJSONObject(index)
                                sample.put(JSONObject().put("oui", row.optString("netid").take(8))
                                    .put("ssid_bytes", row.optString("ssid").toByteArray().size)
                                    .put("country", row.optString("country")).put("frequency", row.optInt("frequency"))
                                    .put("latitude_delta", row.optDouble("trilat", Double.NaN) - lat)
                                    .put("longitude_delta", row.optDouble("trilong", Double.NaN) - lng))
                            }
                            entry.put("sample_shape", sample)
                        }
                    }
                }
            } catch (error: Exception) { entry.put("error", error.javaClass.simpleName) }
            requests.put(entry)
        }
        withContext(NonCancellable) { lease.release() }; lease = null
        val after = provider.status()
        output.putString("diagnosis", JSONObject().put("requests", requests)
            .put("desired_config_unchanged", original == prefs.getString("desired_config", null))
            .put("proxy_before", JSONObject().put("running", before.optBoolean("running")).put("leases", before.optInt("leases", -1)))
            .put("proxy_after", JSONObject().put("running", after.optBoolean("running")).put("leases", after.optInt("leases", -1)))
            .toString())
        output.putString("result", "PASS")
    } catch (error: Exception) {
        output.putString("diagnosis", JSONObject().put("requests", requests).toString())
        output.putString("result", "FAIL: ${error.javaClass.simpleName}")
    } finally {
        withContext(NonCancellable) { lease?.release() }
        http?.connectionPool?.evictAll(); http?.dispatcher?.executorService?.shutdown()
    }
    output
}
