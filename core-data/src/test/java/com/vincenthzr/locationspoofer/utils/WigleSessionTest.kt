package com.vincenthzr.locationspoofer.utils

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class WigleSessionTest {
    private class Fixture(private val needsProxy: Boolean = true) : AutoCloseable {
        val acquisitions = AtomicInteger(); val releases = AtomicInteger(); val requests = AtomicInteger()
        val held = AtomicBoolean()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            if (needsProxy && !held.get()) throw SocketException("Fixture direct route unavailable")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("{}".toResponseBody()).build()
        }.build()
        val transport = WigleTransport(client, RequestProxyLeaseProvider {
            acquisitions.incrementAndGet(); held.set(true)
            object : RequestProxyLease {
                override val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 18765))
                override suspend fun release() { releases.incrementAndGet(); held.set(false) }
            }
        }, clock = { 1000L })
        val request = Request.Builder().url("https://api.wigle.net/api/v2/network/search").build()
        override fun close() { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
    }

    @Test fun sharesOneLeaseAcrossSixPagesAndReleasesItAtTheEnd() = Fixture().use { fixture ->
        runBlocking {
            fixture.transport.withSession { scoped ->
                repeat(6) { scoped.fetch(fixture.request); assertEquals(0, fixture.releases.get()) }
            }
        }
        assertEquals(1, fixture.acquisitions.get()); assertEquals(1, fixture.releases.get())
        assertEquals(7, fixture.requests.get()); assertFalse(fixture.held.get())
    }

    @Test fun aWorkingDirectRouteDoesNotStartAProxy() = Fixture(needsProxy = false).use { fixture ->
        runBlocking { fixture.transport.withSession { scoped -> repeat(3) { scoped.fetch(fixture.request) } } }
        assertEquals(0, fixture.acquisitions.get()); assertEquals(0, fixture.releases.get())
    }

    @Test fun aFailedSearchReleasesTheLease() = Fixture().use { fixture ->
        assertThrows(IOException::class.java) {
            runBlocking { fixture.transport.withSession { scoped -> scoped.fetch(fixture.request); throw IOException("fixture") } }
        }
        assertEquals(1, fixture.acquisitions.get()); assertEquals(1, fixture.releases.get())
        assertFalse(fixture.held.get())
    }

    @Test fun cancellationAlsoReleasesTheLease() = Fixture().use { fixture ->
        runBlocking {
            val job = launch {
                fixture.transport.withSession { scoped -> scoped.fetch(fixture.request); awaitCancellation() }
            }
            withTimeout(5_000) { while (fixture.acquisitions.get() == 0 || fixture.requests.get() < 2) delay(10) }
            job.cancelAndJoin()
        }
        assertEquals(1, fixture.acquisitions.get()); assertEquals(1, fixture.releases.get())
        assertFalse(fixture.held.get())
    }

    @Test fun concurrentPagesCannotLeakDuplicateLeases() = Fixture().use { fixture ->
        runBlocking {
            fixture.transport.withSession { scoped -> coroutineScope {
                (1..6).map { async { scoped.fetch(fixture.request) } }.awaitAll()
            } }
        }
        assertEquals(1, fixture.acquisitions.get()); assertEquals(1, fixture.releases.get())
    }

    @Test fun aLaterSearchGetsANewLeaseAndRetainsOnlyTheRoutingHint() = Fixture().use { fixture ->
        runBlocking { repeat(2) { fixture.transport.withSession { scoped -> scoped.fetch(fixture.request) } } }
        assertEquals(2, fixture.acquisitions.get()); assertEquals(2, fixture.releases.get())
        assertEquals(3, fixture.requests.get()); assertFalse(fixture.held.get())
    }
}
