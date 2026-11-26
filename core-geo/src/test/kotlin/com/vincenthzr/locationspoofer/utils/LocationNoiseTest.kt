package com.vincenthzr.locationspoofer.utils

import com.vincenthzr.locationspoofer.utils.CoordinateUtils.LatLng
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors

class LocationNoiseTest {
    private val base = LatLng(30.6544979, 113.1366294)
    private val epoch = 1_795_000_000_000L
    private fun LocationNoise.Fix.position() = LatLng(latitude, longitude)

    @Test fun repeatedAndOutOfOrderReadsNeverChangeTheSameFix() {
        val expected = LocationNoise.sample(base, epoch, seed = 17)
        repeat(10_000) { index ->
            LocationNoise.sample(base, epoch + index * 701L, seed = 19)
            assertEquals(expected, LocationNoise.sample(base, epoch, seed = 17))
        }
    }

    @Test fun offsetStaysInsideReportedAccuracyAtPolesAndDateline() {
        for (lat in listOf(-90.0, -89.99999, -45.0, 0.0, 60.0, 89.99999, 90.0)) {
            for (lon in listOf(-180.0, -179.999999, 0.0, 179.999999, 180.0)) {
                val origin = LatLng(lat, lon)
                repeat(500) { index ->
                    val fix = LocationNoise.sample(origin, epoch + index * 997L, seed = 31)
                    assertTrue(fix.latitude in -90.0..90.0 && fix.longitude in -180.0..180.0)
                    assertTrue(fix.accuracyM in 1.5f..3.5f)
                    assertTrue(GeoMath.distance(origin, fix.position()) <= 1.20001)
                    assertTrue(GeoMath.distance(origin, fix.position()) < fix.accuracyM)
                }
            }
        }
    }

    @Test fun disabledNoiseLeavesCoordinatesAndAccuracyFixed() {
        for (time in listOf(0L, epoch, epoch + 60_000)) {
            assertEquals(LocationNoise.Fix(base.lat, base.lng, 2.5f), LocationNoise.sample(base, time, false))
            assertEquals(2.5f, LocationNoise.accuracy(time, false), 0.0f)
        }
    }

    @Test fun adjacentFramesAreContinuousAndAccuracyUsesTheSameClock() {
        repeat(5000) { index ->
            val time = epoch + index * 100L
            val a = LocationNoise.sample(base, time)
            val b = LocationNoise.sample(base, time + 100)
            assertTrue(GeoMath.distance(a.position(), b.position()) < 0.05)
            assertTrue(kotlin.math.abs(a.accuracyM - b.accuracyM) < 0.04)
            assertEquals(a.accuracyM, LocationNoise.accuracy(time), 0.0f)
            assertEquals(a, LocationNoise.sample(base, time + 99))
        }
    }

    @Test fun concurrentReadersAgreeDespiteDifferentSeedCacheAccess() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            val expected = LocationNoise.sample(base, epoch, seed = 17)
            val tasks = (0 until 100).map { index -> pool.submit<LocationNoise.Fix> {
                LocationNoise.sample(base, epoch + index, seed = index.toLong())
                LocationNoise.sample(base, epoch, seed = 17)
            } }
            tasks.forEach { assertEquals(expected, it.get()) }
        } finally { pool.shutdownNow() }
    }
}
