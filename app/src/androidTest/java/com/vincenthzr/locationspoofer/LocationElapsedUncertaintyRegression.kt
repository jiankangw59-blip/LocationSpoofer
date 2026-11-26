package com.vincenthzr.locationspoofer

import android.location.Location
import android.os.Bundle
import android.os.Parcel
import android.os.SystemClock
import java.lang.reflect.Method

internal fun SystemFilesInstrumentation.verifyLocationElapsedUncertainty(phase: String?): Bundle = Bundle().apply {
    try {
        require(phase == null || phase == "legacy")
        val legacyOnly = phase == "legacy"
        val loader = targetContext.classLoader
        val prefix = "com.vincenthzr.locationspoofer.xposed"
        val motionType = loader.loadClass("$prefix.utils.SpoofedMotion")
        val double = Double::class.javaPrimitiveType!!
        val float = Float::class.javaPrimitiveType!!
        val motion = motionType.getConstructor(double, double, float, float)
            .newInstance(15.0, 25.0, 0f, 0f)
        val oldOwner = loader.loadClass("$prefix.hooks.SystemHookUtils")
        val oldWriter = oldOwner.getMethod("applyFakeLocationFields", Any::class.java, motionType, double, float)
        val oldReceiver = oldOwner.getField("INSTANCE").get(null)
        val newWriter = if (legacyOnly) null else {
            loader.loadClass("$prefix.hooks.vendor.profiles.versions.RetimedLocationFieldsKt")
                .getMethod("applyRetimedLocationFields", Location::class.java, motionType, double, float)
        }
        val sourceElapsed = SystemClock.elapsedRealtimeNanos() / 2
        val sourceWall = 946_684_800_000L

        val uncertainties = listOf(65.0, 123_456.0, 0.0, null)
        for ((index, uncertainty) in uncertainties.withIndex()) {
            val source = Location("gps").apply {
                latitude = 10.0
                longitude = 20.0
                accuracy = 10f
                elapsedRealtimeNanos = sourceElapsed
                time = sourceWall
                uncertainty?.let { elapsedRealtimeUncertaintyNanos = it }
            }
            check(source.isComplete)
            val (legacy, legacyWall) = retimeCopy(source, motion, oldWriter, oldReceiver)
            putString("legacy_wall_${index + 1}", legacyWall)
            checkUncertainty(legacy, uncertainty)
            checkUncertainty(parcelCopy(legacy), uncertainty)

            if (newWriter != null) {
                val (replacement, replacementWall) = retimeCopy(source, motion, newWriter, null)
                putString("retimed_wall_${index + 1}", replacementWall)
                checkUncertainty(replacement, null)
                checkUncertainty(parcelCopy(replacement), null)
            }

            check(source.elapsedRealtimeNanos == sourceElapsed && source.time == sourceWall)
            check(source.latitude == 10.0 && source.longitude == 20.0 && source.accuracy == 10f)
            checkUncertainty(source, uncertainty)
            checkUncertainty(parcelCopy(source), uncertainty)
        }
        putString("scope", "local Location copies and Parcel on Android ART")
        putString("phase", if (legacyOnly) "legacy" else "comparison")
        putInt("legacy_stale_values_reproduced", uncertainties.count { it != null })
        putInt("retimed_absent_cases", if (legacyOnly) 0 else uncertainties.size)
        putInt("source_unchanged_cases", uncertainties.size)
        putString("result", if (legacyOnly) "LEGACY_OBSERVED" else "PASS")
    } catch (error: Throwable) {
        putString("result", "FAIL: ${error.stackTraceToString()}")
    }
}

private fun retimeCopy(source: Location, motion: Any, writer: Method, receiver: Any?): Pair<Location, String> {
    val copy = Location(source)
    val before = SystemClock.elapsedRealtimeNanos()
    val wallBefore = System.currentTimeMillis()
    writer.invoke(receiver, copy, motion, 20.0, 5f)
    val wallAfter = System.currentTimeMillis()
    val after = SystemClock.elapsedRealtimeNanos()
    check(copy.elapsedRealtimeNanos in before..after) { "Replacement timestamp was not sampled during its write" }
    check(copy.elapsedRealtimeNanos != source.elapsedRealtimeNanos)
    check(copy.time != source.time) { "Replacement retained the source wall-time sentinel" }
    val wallSpan = wallAfter - wallBefore
    val elapsedSpanMillis = (after - before) / 1_000_000
    // A clock step can invalidate a wall-time bracket. Allow one millisecond for quantization.
    val wallComparable = wallSpan in 0L..(elapsedSpanMillis + 1)
    val wallEvidence = "source_ms=${source.time},before_ms=$wallBefore,written_ms=${copy.time}," +
        "after_ms=$wallAfter,elapsed_span_ms=$elapsedSpanMillis"
    if (wallComparable) {
        check(copy.time in wallBefore..wallAfter) { "Replacement wall time was outside its bracket: $wallEvidence" }
    }
    check(copy.isComplete && copy.provider == source.provider)
    check(copy.latitude == 15.0 && copy.longitude == 25.0 && copy.accuracy == 5f && copy.altitude == 20.0)
    val wallResult = if (wallComparable) "BRACKETED" else "UNCOMPARABLE_WALL_STEP"
    return copy to "$wallResult,$wallEvidence"
}

private fun checkUncertainty(location: Location, expected: Double?) {
    check(location.hasElapsedRealtimeUncertaintyNanos() == (expected != null)) {
        "Uncertainty presence did not match for elapsed ${location.elapsedRealtimeNanos}"
    }
    if (expected != null) {
        check(location.elapsedRealtimeUncertaintyNanos == expected)
    }
}

private fun parcelCopy(source: Location): Location {
    val parcel = Parcel.obtain()
    return try {
        source.writeToParcel(parcel, 0)
        parcel.setDataPosition(0)
        Location.CREATOR.createFromParcel(parcel).also { copy ->
            check(copy.elapsedRealtimeNanos == source.elapsedRealtimeNanos && copy.time == source.time)
        }
    } finally {
        parcel.recycle()
    }
}
