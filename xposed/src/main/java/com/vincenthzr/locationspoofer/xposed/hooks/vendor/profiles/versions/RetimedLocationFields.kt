package com.vincenthzr.locationspoofer.xposed.hooks.vendor.profiles.versions

import android.location.Location
import com.vincenthzr.locationspoofer.xposed.hooks.SystemHookUtils
import com.vincenthzr.locationspoofer.xposed.utils.SpoofedMotion

internal fun applyRetimedLocationFields(
    location: Location,
    motion: SpoofedMotion,
    altitude: Double,
    accuracy: Float
) {
    val sourceElapsed = location.elapsedRealtimeNanos
    SystemHookUtils.applyFakeLocationFields(location, motion, altitude, accuracy)
    if (location.elapsedRealtimeNanos != sourceElapsed) {
        // The original uncertainty describes the elapsed timestamp before the rewrite.
        location.removeElapsedRealtimeUncertaintyNanos()
    }
}
