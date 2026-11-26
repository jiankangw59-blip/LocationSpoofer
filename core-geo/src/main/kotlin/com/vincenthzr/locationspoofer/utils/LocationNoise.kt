package com.vincenthzr.locationspoofer.utils

import com.vincenthzr.locationspoofer.utils.CoordinateUtils.LatLng
import kotlin.math.*

/** Time-based receiver noise. Reads never advance state, including reads from different processes. */
object LocationNoise {
    const val DEFAULT_SEED = 0x10CA7105L
    const val FIXED_ACCURACY_M = 2.5f
    private const val MAX_OFFSET_M = 1.2
    private const val FRAME_MS = 100L

    data class Fix(val latitude: Double, val longitude: Double, val accuracyM: Float)

    private class Session(val seed: Long) {
        val north = MotionRealism.SmoothNoise(seed xor 0x4E4F5254L, 25.0, 180.0)
        val east = MotionRealism.SmoothNoise(seed xor 0x45415354L, 30.0, 240.0)
        val accuracy = MotionRealism.SmoothNoise(seed xor 0x41434355L, 20.0, 120.0)
    }

    @Volatile private var cached = Session(DEFAULT_SEED)
    private fun session(seed: Long): Session {
        val value = cached
        return if (value.seed == seed) value else Session(seed).also { cached = it }
    }

    private fun seconds(epochMs: Long): Double = (epochMs / FRAME_MS * FRAME_MS) / 1000.0

    fun accuracy(epochMs: Long, enabled: Boolean = true, seed: Long = DEFAULT_SEED): Float =
        if (!enabled) FIXED_ACCURACY_M else (2.5 + session(seed).accuracy.value(seconds(epochMs))).toFloat()

    fun sample(base: LatLng, epochMs: Long, enabled: Boolean = true, seed: Long = DEFAULT_SEED): Fix {
        require(base.lat.isFinite() && base.lat in -90.0..90.0 && base.lng.isFinite() && base.lng in -180.0..180.0)
        if (!enabled) return Fix(base.lat, base.lng, FIXED_ACCURACY_M)
        val noise = session(seed)
        val time = seconds(epochMs)
        val north = noise.north.value(time)
        val east = noise.east.value(time)
        val length = hypot(north, east)
        if (length == 0.0) return Fix(base.lat, base.lng, accuracy(epochMs, true, seed))

        // A tangent basis avoids degree-scaled offsets and remains stable at the poles/dateline.
        val lat = Math.toRadians(base.lat)
        val lon = Math.toRadians(base.lng)
        val angular = MAX_OFFSET_M * minOf(length, 1.0) / GeoMath.EARTH_RADIUS_M
        val n = north / length * sin(angular)
        val e = east / length * sin(angular)
        val p = cos(angular)
        val x = cos(lat) * cos(lon) * p - sin(lat) * cos(lon) * n - sin(lon) * e
        val y = cos(lat) * sin(lon) * p - sin(lat) * sin(lon) * n + cos(lon) * e
        val z = sin(lat) * p + cos(lat) * n
        return Fix(Math.toDegrees(atan2(z, hypot(x, y))), Math.toDegrees(atan2(y, x)),
            accuracy(epochMs, true, seed))
    }
}
