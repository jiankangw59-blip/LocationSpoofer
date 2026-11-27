package com.vincenthzr.locationspoofer.utils

import com.vincenthzr.locationspoofer.utils.CoordinateUtils.LatLng
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PositionAddressResolverTest {
    private val first = LatLng(25.0, 105.0)
    private val second = LatLng(25.001, 105.001)
    private val third = LatLng(26.0, 106.0)
    private val original = PositionAddress(
        province = "Old province",
        city = "Old city",
        district = "Old district",
        street = "Old street",
        streetNum = "8",
        address = "Old address",
        country = "Old country",
        poiName = "Old name"
    )

    @Test
    fun fixedCoordinatesClearAllOldFieldsWhenGeocodingIsEmptyOrFails() {
        val calls = mutableListOf<LatLng>()
        val resolver = PositionAddressResolver(reverseGeocode = { point ->
            calls += point
            when (point) {
                first -> original
                second -> null
                else -> throw IOException("Unavailable")
            }
        }, isNearby = { _, _ -> true })

        assertEquals(original, resolver.resolve(first, reuseNearby = false))
        assertEquals(PositionAddress(), resolver.resolve(second, reuseNearby = false))
        assertEquals(PositionAddress(), resolver.resolve(second, reuseNearby = false))
        val poi = SelectedPoiAddress(third, "Selected name", "Selected address")
        val failed = resolver.resolve(third, reuseNearby = false)
        assertEquals(PositionAddress(), failed)
        assertEquals(PositionAddress(address = "Selected address", poiName = "Selected name"), failed.withSelectedPoi(third, poi))
        assertEquals(PositionAddress(), failed.withSelectedPoi(second, poi))
        assertEquals(listOf(first, second, third), calls)
    }

    @Test
    fun motionCanReuseNearbyGeocodeWithoutChangingTheFixedPointCacheKey() {
        val next = PositionAddress(address = "Next address")
        val calls = mutableListOf<LatLng>()
        val resolver = PositionAddressResolver(reverseGeocode = { point ->
            calls += point
            if (point == first) original else next
        }, isNearby = { origin, point -> origin == first && point == second })

        assertEquals(original, resolver.resolve(first, reuseNearby = true))
        assertEquals(original, resolver.resolve(second, reuseNearby = true))
        assertEquals(next, resolver.resolve(second, reuseNearby = false))
        assertEquals(listOf(first, second), calls)
    }

    @Test
    fun anOlderLookupCannotClaimItsObservedCacheAfterNewerQueryFinishes() {
        val comparing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val next = PositionAddress(address = "New address")
        val resolver = PositionAddressResolver(reverseGeocode = { point ->
            if (point == third) next else original
        }, isNearby = { origin, point ->
            if (origin == first && point == second) {
                comparing.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            false
        })
        val executor = Executors.newSingleThreadExecutor()
        try {
            assertEquals(original, resolver.resolve(first, reuseNearby = false))
            val stale = executor.submit<PositionAddress> { resolver.resolve(second, reuseNearby = true) }
            assertTrue(comparing.await(5, TimeUnit.SECONDS))
            assertEquals(next, resolver.resolve(third, reuseNearby = false))
            release.countDown()
            assertEquals(original, stale.get(5, TimeUnit.SECONDS))
            assertEquals(next, resolver.resolve(LatLng(26.0000001, 106.0), reuseNearby = false))
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun cancellationDuringGeocodingCannotCommitDesiredOrItsMirrors() = runBlocking {
        val geocoding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val resolver = PositionAddressResolver(reverseGeocode = {
            geocoding.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            original
        }, isNearby = { _, _ -> false })
        val writeMutex = Mutex()
        val writes = mutableListOf<String>()
        val pending = launch(Dispatchers.IO) {
            // A blocking lookup may return even after the original caller was cancelled.
            resolver.resolve(first, reuseNearby = false)
            writeMutex.writeCurrentPosition {
                writes += listOf("desired", "settings", "runtime")
                true
            }
        }
        try {
            assertTrue(geocoding.await(5, TimeUnit.SECONDS))
            pending.cancel()
        } finally {
            release.countDown()
        }
        pending.join()
        assertTrue(writes.isEmpty())

        writeMutex.writeCurrentPosition {
            writes += listOf("new desired", "new settings", "new runtime")
            true
        }
        assertEquals(listOf("new desired", "new settings", "new runtime"), writes)
    }

    @Test
    fun cancelledPublisherCannotDiscardAcceptedLifecycleOrOvertakeStop() = runBlocking {
        val mutex = Mutex()
        val accepted = CountDownLatch(1)
        val completePublication = CountDownLatch(1)
        val main = ConcurrentLinkedQueue<() -> Unit>()
        val lifecycle = mutableListOf<Pair<String, Boolean>>()
        var desiredActive = false

        fun enqueue(active: Boolean) {
            main.add {
                lifecycle += "service" to active
                lifecycle += "ui" to active
            }
        }

        val pendingStart = launch {
            withContext(Dispatchers.IO) {
                mutex.writeCurrentPosition {
                    desiredActive = true
                    enqueue(true)
                    accepted.countDown()
                    check(completePublication.await(5, TimeUnit.SECONDS))
                    true
                }
            }
            error("A cancelled publisher must not rely on this continuation")
        }

        try {
            assertTrue(withContext(Dispatchers.IO) { accepted.await(5, TimeUnit.SECONDS) })
            pendingStart.cancel()
        } finally {
            completePublication.countDown()
        }
        pendingStart.join()
        assertTrue(desiredActive)
        assertEquals(1, main.size)

        mutex.writeCurrentPosition {
            desiredActive = false
            enqueue(false)
            true
        }
        assertEquals(false, desiredActive)
        while (main.isNotEmpty()) main.remove().invoke()
        assertEquals(listOf("service" to true, "ui" to true, "service" to false, "ui" to false), lifecycle)
    }

    @Test
    fun aDelayedLookupCannotReplaceTheNewerPointCache() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val next = PositionAddress(address = "Next address")
        var nextCalls = 0
        val resolver = PositionAddressResolver(reverseGeocode = { point ->
            if (point == first) {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                original
            } else {
                nextCalls++
                next
            }
        }, isNearby = { _, _ -> true })
        val executor = Executors.newSingleThreadExecutor()

        try {
            val delayed = executor.submit<PositionAddress> { resolver.resolve(first, reuseNearby = false) }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertEquals(next, resolver.resolve(second, reuseNearby = false))
            release.countDown()
            assertEquals(original, delayed.get(5, TimeUnit.SECONDS))
            assertEquals(next, resolver.resolve(second, reuseNearby = true))
            assertEquals(1, nextCalls)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}
