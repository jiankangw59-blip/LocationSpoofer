package com.vincenthzr.locationspoofer.utils

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Shared request-scoped networking for WiGLE profile, Wi-Fi and Bluetooth APIs. */
class WigleTransport(
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build(),
    private val proxyProvider: RequestProxyLeaseProvider? = null,
    private val clock: () -> Long = System::currentTimeMillis
) {
    @Volatile private var preferProxyUntil = 0L

    /** A finite lookup shares one lazy lease across its pages/radii, then releases it even on cancellation. */
    internal suspend fun <T> withSession(block: suspend (WigleTransport) -> T): T {
        val upstream = proxyProvider ?: return block(this)
        val lock = Mutex()
        var held: RequestProxyLease? = null
        var closed = false
        val scopedProvider = RequestProxyLeaseProvider {
            lock.withLock {
                check(!closed) { "WiGLE request session closed" }
                val lease = held ?: upstream.acquire()?.also { held = it }
                lease?.let { actual -> object : RequestProxyLease {
                    override val proxy = actual.proxy
                    override suspend fun release() = Unit
                } }
            }
        }
        val scoped = WigleTransport(client, scopedProvider, clock).also { it.preferProxyUntil = preferProxyUntil }
        try { return block(scoped) }
        finally {
            preferProxyUntil = maxOf(preferProxyUntil, scoped.preferProxyUntil)
            withContext(NonCancellable) {
                val actual = lock.withLock { closed = true; held.also { held = null } }
                actual?.let { lease ->
                    runCatching { lease.release() }.onFailure {
                        android.util.Log.w("LocationSpoofer", "Private proxy release unavailable; broker TTL will reclaim it")
                    }
                }
            }
        }
    }

    suspend fun fetch(request: Request): ByteArray {
        require(request.url.scheme == "https" && request.url.host == "api.wigle.net" && request.url.port == 443) { "WiGLE origin required" }
        if (clock() < preferProxyUntil) fetchUsingLease(request)?.let { return it }
        try { return fetchDirect(request, client) }
        catch (error: IOException) {
            if (error !is java.net.SocketTimeoutException && error !is java.net.UnknownHostException &&
                error !is java.net.SocketException && error !is javax.net.ssl.SSLException &&
                error !is java.io.InterruptedIOException) throw error
            val bytes = fetchUsingLease(request) ?: throw error
            preferProxyUntil = clock() + 15 * 60_000L
            return bytes
        }
    }

    private suspend fun fetchUsingLease(request: Request): ByteArray? {
        val provider = proxyProvider ?: return null
        // Acquisition is bounded by the broker's HTTP timeout. Cancellation must not lose a returned lease.
        val lease = withContext(NonCancellable) { provider.acquire() } ?: return null
        try {
            currentCoroutineContext().ensureActive()
            return fetchDirect(request, client.newBuilder().proxy(lease.proxy).build())
        } finally {
            withContext(NonCancellable) {
                runCatching { lease.release() }.onFailure {
                    android.util.Log.w("LocationSpoofer", "Private proxy release unavailable; broker TTL will reclaim it")
                }
            }
        }
    }

    private suspend fun fetchDirect(request: Request, transport: OkHttpClient): ByteArray = suspendCancellableCoroutine { continuation ->
        val call = transport.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val bytes = response.use {
                        if (!it.isSuccessful) throw RemoteWifiQueryException(it.code)
                        val output = ByteArrayOutputStream()
                        val body = it.body ?: throw IOException("Missing WiGLE response")
                        body.byteStream().use { input ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (output.size() + count > 2_097_152) throw IOException("WiGLE response too large")
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
