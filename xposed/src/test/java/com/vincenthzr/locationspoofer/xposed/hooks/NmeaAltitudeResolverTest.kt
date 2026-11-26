package com.vincenthzr.locationspoofer.xposed.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class NmeaAltitudeResolverTest {
    private val firstGeneration = Any()

    private class Model : NmeaAltitudeModel {
        var loadCount = 0
        var lookupCount = 0
        var cached: (Double, Double, Double) -> Double? = { _, _, _ -> null }
        var load: (Double, Double, Double) -> Double? = { _, _, _ -> null }
        var rmseMeters: Float? = null

        override fun cachedMeanSeaLevel(latitude: Double, longitude: Double, ellipsoidMeters: Double): ModelMslAltitude? {
            lookupCount++
            return cached(latitude, longitude, ellipsoidMeters)?.let { ModelMslAltitude(it, rmseMeters) }
        }

        override fun loadMeanSeaLevel(latitude: Double, longitude: Double, ellipsoidMeters: Double): ModelMslAltitude? {
            loadCount++
            return load(latitude, longitude, ellipsoidMeters)?.let { ModelMslAltitude(it, rmseMeters) }
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
        val lookups = model.lookupCount
        assertNull(resolver.cachedForLocation(2, 12.0, 23.0, 100.0))
        assertEquals(lookups, model.lookupCount)
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

    @Test fun locationProjectionRequiresFinePermissionAndKeepsEachFixIndependent() {
        val model = Model().apply { rmseMeters = 4f }
        val resolver = NmeaAltitudeResolver(model)
        assertNull(resolver.cachedForLocation(2, 12.0, 23.0, 100.0))
        assertEquals(0, model.lookupCount)
        model.load = { _, _, h ->
            model.cached = { latitude, longitude, height ->
                when (latitude to longitude) {
                    12.0 to 23.0 -> height - 0.75
                    13.0 to 24.0 -> height - 1.25
                    else -> null
                }
            }
            h - 0.75
        }
        resolver.resolveOnWorker(firstGeneration, 12.0, 23.0, 100.0)
        val lookups = model.lookupCount
        assertNull(resolver.cachedForLocation(1, 12.0, 23.0, 100.0))
        assertNull(resolver.cachedForLocation(null, 12.0, 23.0, 100.0))
        assertEquals(lookups, model.lookupCount)
        val first = checkNotNull(resolver.cachedForLocation(2, 12.0, 23.0, 100.0))
        assertEquals(NmeaAltitude(99.25, 0.75, 4f), first)
        val second = checkNotNull(resolver.cachedForLocation(2, 13.0, 24.0, 20.0))
        assertEquals(NmeaAltitude(18.75, 1.25, 4f), second)
        model.rmseMeters = Float.NaN
        assertEquals(NmeaAltitude(99.25, 0.75), resolver.cachedForLocation(2, 12.0, 23.0, 100.0))
        assertNull(resolver.cachedForLocation(2, 14.0, 25.0, 100.0))
        assertEquals(1, model.loadCount)
    }

    @Test fun locationPrecisionCombinesOnlyFiniteNonnegativeUncertainties() {
        val height = NmeaAltitude(99.25, 0.75, 4f)
        assertEquals(5f, checkNotNull(height.locationAccuracyMeters(3f)), 0f)
        assertEquals(4f, checkNotNull(height.locationAccuracyMeters(0f)), 0f)
        for (invalid in listOf(null, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertNull(height.locationAccuracyMeters(invalid))
            assertNull(height.copy(modelRmseMeters = invalid).locationAccuracyMeters(3f))
        }
        assertNull(height.copy(modelRmseMeters = Float.MAX_VALUE).locationAccuracyMeters(Float.MAX_VALUE))
    }

    @Test fun callsFromRetiredConfigurationDoNotConsumeOrReplayWarmup() {
        val nextGeneration = Any()
        var current = firstGeneration
        val model = Model().apply {
            load = { _, _, h ->
                current = nextGeneration
                h - 0.75
            }
        }
        val resolver = NmeaAltitudeResolver(model)
        assertNull(resolver.resolveOnWorker(firstGeneration, 12.0, 23.0, 100.0) { current === firstGeneration })
        assertEquals(1, model.loadCount)
        val next = resolver.resolveOnWorker(nextGeneration, 13.0, 24.0, 20.0) { current === nextGeneration }
        assertEquals(NmeaAltitude(19.25, 0.75), next)
        assertNull(resolver.resolveOnWorker(firstGeneration, 12.0, 23.0, 100.0) { current === firstGeneration })
        assertNull(resolver.resolveOnWorker(nextGeneration, 13.0, 24.0, 20.0) { current === nextGeneration })
        assertEquals(2, model.loadCount)
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

    @Test fun busyConverterDoesNotDelayBinderOrConsumeAnotherWarmup() {
        val workerEnteredLoad = CountDownLatch(1)
        val releaseLoad = CountDownLatch(1)
        val model = Model().apply { load = { _, _, h -> h - 0.75 } }
        val resolver = NmeaAltitudeResolver(model)
        assertEquals(NmeaAltitude(99.25, 0.75), resolver.resolveOnWorker(firstGeneration, 12.0, 23.0, 100.0))
        model.load = { _, _, input ->
            workerEnteredLoad.countDown()
            assertTrue(releaseLoad.await(10, TimeUnit.SECONDS))
            input - 0.75
        }
        val nextGeneration = Any()
        val postponedGeneration = Any()
        val current = AtomicReference(nextGeneration)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val load = workers.submit<NmeaAltitude?> {
                resolver.resolveOnWorker(nextGeneration, 12.0, 23.0, 100.0) { current.get() === nextGeneration }
            }
            assertTrue(workerEnteredLoad.await(5, TimeUnit.SECONDS))
            current.set(postponedGeneration)
            val binder = workers.submit<List<NmeaAltitude?>> {
                listOf(resolver.cached(12.0, 23.0, 100.0), resolver.cachedForLocation(2, 12.0, 23.0, 100.0))
            }
            assertEquals(listOf(null, null), binder.get(2, TimeUnit.SECONDS))
            val firstAttempt = workers.submit<NmeaAltitude?> {
                resolver.resolveOnWorker(postponedGeneration, 13.0, 24.0, 20.0) {
                    current.get() === postponedGeneration
                }
            }
            assertNull(firstAttempt.get(2, TimeUnit.SECONDS))
            releaseLoad.countDown()
            assertNull(load.get(5, TimeUnit.SECONDS))
            assertEquals(2, model.lookupCount)
            assertEquals(2, model.loadCount)
            val retried = resolver.resolveOnWorker(postponedGeneration, 13.0, 24.0, 20.0) {
                current.get() === postponedGeneration
            }
            assertEquals(NmeaAltitude(19.25, 0.75), retried)
            assertNull(resolver.resolveOnWorker(postponedGeneration, 13.0, 24.0, 20.0))
            assertEquals(3, model.loadCount)
        } finally {
            releaseLoad.countDown()
            workers.shutdownNow()
        }
    }
}
