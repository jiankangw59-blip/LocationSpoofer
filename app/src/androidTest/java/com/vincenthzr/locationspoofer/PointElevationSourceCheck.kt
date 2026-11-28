package com.vincenthzr.locationspoofer

import android.annotation.TargetApi
import android.app.Instrumentation
import android.content.Context
import android.location.Location
import android.location.altitude.AltitudeConverter
import android.os.Build
import android.os.Bundle
import com.vincenthzr.locationspoofer.data.elevation.TargetElevationSource
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.math.abs

/** Public reference samples only. No provider subscription, live coordinates, app preferences or simulation. */
internal fun Instrumentation.checkPointElevationSource(): Bundle = Bundle().apply {
    if (Build.VERSION.SDK_INT < 34) {
        putString("result", "UNAVAILABLE_PLATFORM")
        return@apply
    }
    val ctx = targetContext
    val samples = listOf(
        ElevationSample("berlin", 52.52, 13.41, 20.0..75.0),
        ElevationSample("dead_sea", 31.50, 35.50, -480.0..-320.0)
    )
    val statuses = runBlocking {
        samples.map { point ->
            val status = try {
                checkElevationSample(ctx, point, this@apply)
            } catch (e: Exception) {
                "FAIL_" + e.javaClass.simpleName
            }
            putString(point.name + "_status", status)
            status
        }
    }
    putString("result", when {
        statuses.all { it == "PASS" } -> "PASS"
        statuses.any { it.startsWith("FAIL") } -> "FAIL"
        else -> "UNAVAILABLE"
    })
}

private data class ElevationSample(
    val name: String, val lat: Double, val lon: Double, val expectedMsl: ClosedFloatingPointRange<Double>
)

@TargetApi(34)
private suspend fun checkElevationSample(ctx: Context, point: ElevationSample, out: Bundle): String {
    fun project(h: Double): Double? {
        val input = Location("height-reference-test").apply {
            latitude = point.lat; longitude = point.lon; altitude = h
        }
        return try {
            AltitudeConverter().addMslAltitudeToLocation(ctx, input)
            if (input.hasMslAltitude()) input.mslAltitudeMeters.takeIf { it.isFinite() } else null
        } catch (_: IOException) { null }
    }
    val zeroMsl = project(0.0) ?: return "UNAVAILABLE_VERTICAL_MODEL"
    val actual = TargetElevationSource.lookup(ctx, point.lat, point.lon) ?: return "UNAVAILABLE_SOURCE_OR_TIMEOUT"
    val roundTrip = project(actual.ellipsoidMeters) ?: return "UNAVAILABLE_VERTICAL_MODEL"
    val error = abs(roundTrip - actual.surfaceMslMeters)
    out.putDouble(point.name + "_surface_msl_m", actual.surfaceMslMeters)
    out.putDouble(point.name + "_ellipsoid_m", actual.ellipsoidMeters)
    out.putDouble(point.name + "_roundtrip_error_m", error)
    if (actual.surfaceMslMeters !in point.expectedMsl) return "FAIL_SURFACE_RANGE"
    if (abs(zeroMsl) < 2.0 || error > 0.05 || !error.isFinite()) return "FAIL_DATUM"
    return "PASS"
}
