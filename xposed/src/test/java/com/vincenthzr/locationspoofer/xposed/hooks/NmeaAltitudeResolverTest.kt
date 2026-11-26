package com.vincenthzr.locationspoofer.xposed.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NmeaAltitudeResolverTest {
    private val firstGeneration = Any()

    private class Model : NmeaAltitudeModel {
        var loadCount = 0
        var lookupCount = 0
        var cached: (Double, Double, Double) -> Double? = { _, _, _ -> null }
        var load: (Double, Double, Double) -> Double? = { _, _, _ -> null }

        override fun cachedMeanSeaLevel(latitude: Double, longitude: Double, ellipsoidMeters: Double): Double? {
            lookupCount++
            return cached(latitude, longitude, ellipsoidMeters)
        }

        override fun loadMeanSeaLevel(latitude: Double, longitude: Double, ellipsoidMeters: Double): Double? {
            loadCount++
            return load(latitude, longitude, ellipsoidMeters)
        }
    }

    @Test fun binderCacheMissAndInvalidCoordinatesNeverLoadAssets() {
        val model = Model()
        val resolver = NmeaAltitudeResolver(model)
        assertNull(resolver.cached(12.0, 23.0, 100.0))
        assertNull(resolver.cached(Double.NaN, 23.0, 100.0))
        assertNull(resolver.resolveOnWorker(firstGeneration, 12.0, 181.0, 100.0))
        assertNull(resolver.resolveOnWorker(firstGeneration, 12.0, 23.0, Double.POSITIVE_INFINITY))
        assertEquals(1, model.lookupCount)
        assertEquals(0, model.loadCount)
    }

    @Test fun workerLoadsAtMostOncePerConfigEvenWhenAssetLoadFails() {
        val model = Model().apply { load = { _, _, _ -> throw IOException("missing geoid asset") } }
        val attempts = mutableListOf<Boolean>()
        val resolver = NmeaAltitudeResolver(model) { attempts.add(it) }
        repeat(3) { assertNull(resolver.resolveOnWorker(firstGeneration, 12.0, 23.0, 100.0)) }
        assertEquals(1, model.loadCount)
        val nextGeneration = Any()
        model.load = { _, _, h -> h - 0.75 }
        assertEquals(NmeaAltitude(99.25, 0.75), resolver.resolveOnWorker(nextGeneration, 12.0, 23.0, 100.0))
        assertNull(resolver.resolveOnWorker(nextGeneration, 13.0, 24.0, 100.0))
        assertEquals(2, model.loadCount)
        assertEquals(listOf(false, true), attempts)
    }

    @Test fun projectionUsesEachFixCoordinatesAndEllipsoidHeight() {
        val model = Model()
        val resolver = NmeaAltitudeResolver(model)
        val warmLatitude = 12.0
        val warmLongitude = 23.0
        model.load = { latitude, longitude, h ->
            assertEquals(warmLatitude, latitude, 0.0)
            assertEquals(warmLongitude, longitude, 0.0)
            assertEquals(100.0, h, 0.0)
            model.cached = { lat, lon, ellipsoid ->
                if (lat == warmLatitude && lon == warmLongitude) ellipsoid - 0.75 else null
            }
            h - 0.75
        }
        assertEquals(NmeaAltitude(99.25, 0.75),
            resolver.resolveOnWorker(firstGeneration, warmLatitude, warmLongitude, 100.0))
        assertEquals(NmeaAltitude(-0.75, 0.75), resolver.cached(warmLatitude, warmLongitude, 0.0))
        assertNull(resolver.cached(13.0, 24.0, 100.0))
        assertEquals(1, model.loadCount)
    }

    @Test fun nonFiniteModelResultsAreUnknown() {
        val model = Model().apply { cached = { _, _, _ -> Double.NaN } }
        val resolver = NmeaAltitudeResolver(model)
        assertNull(resolver.cached(12.0, 23.0, 100.0))
        model.cached = { _, _, _ -> -Double.MAX_VALUE }
        assertNull(resolver.cached(12.0, 23.0, Double.MAX_VALUE))
        assertEquals(0, model.loadCount)
    }

    @Test fun missingNativeApiIsUnknownWithoutRepeatedLookups() {
        val model = Model().apply {
            cached = { _, _, _ -> throw NoSuchMethodError("AltitudeConverter.tryAddMslAltitudeToLocation") }
        }
        val resolver = NmeaAltitudeResolver(model)
        assertNull(resolver.cached(12.0, 23.0, 100.0))
        assertNull(resolver.resolveOnWorker(firstGeneration, 12.0, 23.0, 100.0))
        assertNull(resolver.resolveOnWorker(Any(), 13.0, 23.0, 100.0))
        assertEquals(1, model.lookupCount)
        assertEquals(0, model.loadCount)
    }

    @Test fun binderDoesNotWaitForAnAssetLoadInProgress() {
        val workerEnteredLoad = CountDownLatch(1)
        val releaseLoad = CountDownLatch(1)
        val model = Model().apply {
            load = { _, _, input ->
                workerEnteredLoad.countDown()
                assertTrue(releaseLoad.await(10, TimeUnit.SECONDS))
                input - 0.75
            }
        }
        val resolver = NmeaAltitudeResolver(model)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val load = workers.submit<NmeaAltitude?> { resolver.resolveOnWorker(firstGeneration, 12.0, 23.0, 100.0) }
            assertTrue(workerEnteredLoad.await(5, TimeUnit.SECONDS))
            val binder = workers.submit<NmeaAltitude?> { resolver.cached(12.0, 23.0, 100.0) }
            assertNull(binder.get(2, TimeUnit.SECONDS))
            releaseLoad.countDown()
            assertEquals(NmeaAltitude(99.25, 0.75), load.get(5, TimeUnit.SECONDS))
            assertEquals(1, model.lookupCount)
            assertEquals(1, model.loadCount)
        } finally {
            releaseLoad.countDown()
            workers.shutdownNow()
        }
    }
}
