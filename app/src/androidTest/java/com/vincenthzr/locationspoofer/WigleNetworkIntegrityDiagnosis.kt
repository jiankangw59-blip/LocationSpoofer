package com.vincenthzr.locationspoofer

import android.os.Bundle
import android.os.SystemClock
import com.vincenthzr.locationspoofer.data.repository.SettingsRepository
import com.vincenthzr.locationspoofer.utils.FeedMeProxyLeaseProvider
import com.vincenthzr.locationspoofer.utils.RequestProxyLease
import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.koin.core.context.GlobalContext
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProtocolException
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private fun wigleProxyClient(lease: RequestProxyLease) = OkHttpClient.Builder()
    .proxy(lease.proxy).connectTimeout(6, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
    .callTimeout(15, TimeUnit.SECONDS)

/** Verify origin identity and authentication behavior before trusting unusually shaped source data. */
internal fun SystemFilesInstrumentation.diagnoseWigleNetworkIntegrity(): Bundle = runBlocking {
    val output = Bundle()
    var http: OkHttpClient? = null
    var lease: RequestProxyLease? = null
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
        http = wigleProxyClient(lease).build()
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

/** One official profile request with a fresh, explicitly proxied client. */
internal fun SystemFilesInstrumentation.diagnoseWigleProfileTls(http1Only: Boolean): Bundle = runBlocking {
    val output = Bundle()
    output.putBoolean("httpSuccess", false)
    output.putBoolean("authSuccess", false)
    output.putBoolean("tlsHandshakeSucceeded", false)
    output.putBoolean("http1Only", http1Only)
    output.putBoolean("leaseReleased", false)
    val stage = AtomicReference("app_service")
    val tlsVersion = AtomicReference<String?>()
    val observedProtocol = AtomicReference<String?>()
    val observedStatus = AtomicInteger(-1)
    val requestIssued = AtomicBoolean()
    var provider: FeedMeProxyLeaseProvider? = null
    var lease: RequestProxyLease? = null
    var http: OkHttpClient? = null
    var requestStart: Long? = null
    try {
        val koin = withTimeout(20_000) {
            while (GlobalContext.getOrNull()?.getOrNull<FeedMeProxyLeaseProvider>() == null) delay(100)
            GlobalContext.get()
        }
        stage.set("credential")
        val token = koin.get<SettingsRepository>().getWigleApiToken().trim()
        check(token.isNotEmpty())
        val proxyProvider = koin.get<FeedMeProxyLeaseProvider>()
        provider = proxyProvider
        stage.set("lease")
        val before = proxyProvider.status()
        output.putInt("leasesBefore", before.optInt("leases", -1))
        output.putBoolean("runningBefore", before.optBoolean("running"))
        val ownedLease = checkNotNull(withContext(NonCancellable) { proxyProvider.acquire() })
        lease = ownedLease
        val client = wigleProxyClient(ownedLease).retryOnConnectionFailure(false)
            .followRedirects(false).followSslRedirects(false)
            .addNetworkInterceptor { chain ->
                // OkHttp can follow up HTTP 503 with Retry-After: 0 even when I/O retries are disabled.
                if (!requestIssued.compareAndSet(false, true)) throw ProtocolException("Profile follow-up prevented")
                chain.proceed(chain.request())
            }
            .eventListener(object : EventListener() {
                override fun callStart(call: Call) { stage.set("request_start") }
                override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
                    stage.set("tcp_or_proxy_connect")
                }
                override fun secureConnectStart(call: Call) { stage.set("tls_handshake") }
                override fun secureConnectEnd(call: Call, handshake: Handshake?) {
                    tlsVersion.set(handshake?.tlsVersion?.javaName)
                    stage.set("tls_complete")
                }
                override fun requestHeadersStart(call: Call) { stage.set("request_headers") }
                override fun responseHeadersStart(call: Call) { stage.set("response_headers") }
                override fun responseHeadersEnd(call: Call, response: Response) {
                    observedStatus.set(response.code)
                    observedProtocol.set(response.protocol.toString())
                }
                override fun responseBodyStart(call: Call) { stage.set("response_body") }
            }).apply {
                if (http1Only) protocols(listOf(Protocol.HTTP_1_1))
            }.build()
        http = client
        val request = Request.Builder().url("https://api.wigle.net/api/v2/profile/user")
            .header("Authorization", "Basic $token").header("Accept", "application/json").build()
        requestStart = SystemClock.elapsedRealtime()
        withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                val body = response.peekBody(65_536L).string()
                val profile = runCatching { JSONObject(body) }.getOrNull()
                output.putBoolean("authSuccess", response.isSuccessful && profile?.optBoolean("success") == true)
            }
        }
        stage.set("response_complete")
    } catch (error: Exception) {
        output.putString("failureType", error.javaClass.simpleName)
        output.putString("failureCauseTypes", generateSequence<Throwable>(error) { it.cause }
            .take(6).joinToString(" > ") { it.javaClass.simpleName })
        val messages = generateSequence<Throwable>(error) { it.cause }.take(6).map { it.message.orEmpty().lowercase() }.toList()
        val reason = when {
            messages.any { "certificate" in it || "trust anchor" in it } -> "certificate_or_trust"
            messages.any { "protocol version" in it || "protocol_version" in it } -> "protocol_version"
            messages.any { "handshake_failure" in it || "handshake failure" in it } -> "handshake_alert"
            messages.any { "reset" in it } -> "connection_reset"
            messages.any { "timed out" in it || "timeout" in it } -> "timeout"
            messages.any { "closed" in it || "eof" in it } -> "peer_closed_or_eof"
            else -> "other"
        }
        output.putString("failureReason", reason)
    } finally {
        output.putString("stage", stage.get())
        output.putBoolean("httpRequestIssued", requestIssued.get())
        output.putBoolean("tlsHandshakeSucceeded", tlsVersion.get() != null)
        tlsVersion.get()?.let { output.putString("tlsVersion", it) }
        observedProtocol.get()?.let { output.putString("protocol", it) }
        observedStatus.get().takeIf { it >= 100 }?.let {
            output.putInt("httpStatus", it)
            output.putBoolean("httpSuccess", it in 200..299)
        }
        requestStart?.let { output.putLong("requestElapsedMs", SystemClock.elapsedRealtime() - it) }
        withContext(NonCancellable) {
            lease?.let { owned ->
                val released = runCatching { owned.release() }
                output.putBoolean("leaseReleased", released.isSuccess)
                released.exceptionOrNull()?.let { output.putString("leaseReleaseFailure", it.javaClass.simpleName) }
            }
            provider?.let { value ->
                runCatching { value.status() }.onSuccess { after ->
                    output.putInt("leasesAfter", after.optInt("leases", -1))
                    output.putBoolean("runningAfter", after.optBoolean("running"))
                }.onFailure { output.putString("leaseStatusFailure", it.javaClass.simpleName) }
            }
        }
        http?.connectionPool?.evictAll()
        http?.dispatcher?.executorService?.shutdown()
    }
    output.putString("result", when {
        lease != null && !output.getBoolean("leaseReleased") -> "CLEANUP_INCOMPLETE"
        requestStart == null -> "SETUP_FAILED"
        else -> "COMPLETE"
    })
    output
}
