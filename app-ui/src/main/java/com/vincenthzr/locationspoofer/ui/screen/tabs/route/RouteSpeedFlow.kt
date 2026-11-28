package com.vincenthzr.locationspoofer.ui.screen.tabs.route

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

internal fun displaySpeed(speedMs: Double): Double = (speedMs * 10).roundToInt() / 10.0

/** Position updates alone must not invalidate the speed label. */
internal fun <T> Flow<T>.displaySpeeds(speedMs: (T) -> Double): Flow<Double> =
    map { displaySpeed(speedMs(it)) }.distinctUntilChanged()
