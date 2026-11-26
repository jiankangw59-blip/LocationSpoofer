package com.vincenthzr.locationspoofer.xposed.hooks

import com.vincenthzr.locationspoofer.utils.MotionRealism
import kotlin.math.*

/** One synthetic sky shared by GNSS and NMEA. It does not claim to be a live ephemeris calculation. */
internal object SatelliteSky {
    data class Satellite(val constellation: Int, val svid: Int, val cn0: Float, val elevation: Float,
        val azimuth: Float, val used: Boolean, val carrierHz: Float?) {
        val talker: String get() = when (constellation) { 1 -> "GP"; 3 -> "GL"; 5 -> "GB"; else -> "GA" }
        val systemId: Int get() = when (constellation) { 1 -> 1; 3 -> 2; 5 -> 4; else -> 3 }
    }
    private val types = intArrayOf(5,5,5,5,5,5,5,5,5,5,5,5, 1,1,1,1,1,1,1,1,1,1,1,1, 3,3,3,3, 6,6,6,6)
    private val ids = intArrayOf(1,2,3,5,6,7,8,9,10,11,13,14, 1,3,6,7,8,9,11,14,17,19,21,22, 1,2,3,4, 1,2,3,5)
    private val elevations = floatArrayOf(78f,65f,54f,42f,38f,72f,58f,49f,33f,61f,45f,29f,
        82f,69f,51f,44f,36f,75f,63f,47f,31f,59f,41f,25f,55f,43f,35f,28f,60f,48f,39f,22f)
    private val azimuths = floatArrayOf(12f,45f,78f,112f,145f,178f,212f,245f,278f,312f,335f,25f,
        35f,68f,95f,132f,165f,198f,232f,265f,298f,325f,15f,52f,85f,175f,265f,345f,45f,135f,225f,315f)
    private val signalNoise = MotionRealism.SmoothNoise(0x534B594CL, 20.0, 90.0)

    fun packed(satellite: Satellite, svidShift: Int, constellationShift: Int, baseband: Boolean): Int {
        val flags = 1 or 2 or (if (satellite.used) 4 else 0) or
            (if (satellite.carrierHz != null) 8 else 0) or (if (baseband) 16 else 0)
        return (satellite.svid shl svidShift) or (satellite.constellation shl constellationShift) or flags
    }

    fun snapshot(count: Int, epochMs: Long, jitter: Boolean): List<Satellite> {
        val seconds = (epochMs / 1000L).toDouble()
        val values = (0 until count.coerceIn(4, 32)).map { index ->
            val phase = seconds * 2 * PI / 7200.0 + index * 0.73
            val elevation = (elevations[index] + 6 * sin(phase)).toFloat().coerceIn(10f, 88f)
            val azimuth = ((azimuths[index] + seconds / 120.0) % 360.0).toFloat()
            val noise = if (jitter) signalNoise.value(seconds + index * 3.79) * 1.2 else 0.0
            val cn0 = (34 + elevation / 90.0 * 8 + noise).toFloat().coerceIn(30f, 43.5f)
            val carrier = when (types[index]) { 3 -> null; 5 -> 1_561_098_000f; else -> 1_575_420_000f }
            Satellite(types[index], ids[index], cn0, elevation, azimuth, false, carrier)
        }
        val used = values.indices.sortedByDescending { values[it].elevation }.take(minOf(values.size, 12)).toSet()
        return values.mapIndexed { index, value -> value.copy(used = index in used) }
    }
}
