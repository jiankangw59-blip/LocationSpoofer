package com.vincenthzr.locationspoofer.utils

import kotlin.math.log10
import kotlin.math.roundToInt

/** Deliberately simple simulation estimates, never represented as radio measurements. */
object RemoteSignalModel {
    fun wifi(distanceMeters: Double): Int = estimate(distanceMeters, -40.0, 2.0, -110..-35)
    fun cell(distanceMeters: Double): Int = estimate(distanceMeters, -55.0, 2.0, -140..-45)

    private fun estimate(distance: Double, reference: Double, exponent: Double, range: IntRange): Int {
        require(distance.isFinite() && distance >= 0)
        return (reference - 10 * exponent * log10(distance.coerceAtLeast(1.0))).roundToInt().coerceIn(range)
    }
}
