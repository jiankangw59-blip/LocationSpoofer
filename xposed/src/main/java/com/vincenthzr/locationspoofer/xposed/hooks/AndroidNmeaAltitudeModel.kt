package com.vincenthzr.locationspoofer.xposed.hooks

import android.annotation.TargetApi
import android.content.Context
import android.location.Location
import android.location.altitude.AltitudeConverter

@TargetApi(35)
internal fun newAndroidNmeaAltitudeResolver(
    context: () -> Context?,
    onWarmResult: (Boolean) -> Unit = {}
): NmeaAltitudeResolver = NmeaAltitudeResolver(AndroidNmeaAltitudeModel(context), onWarmResult)

@TargetApi(35)
private class AndroidNmeaAltitudeModel(private val context: () -> Context?) : NmeaAltitudeModel {
    private val converter = AltitudeConverter()

    override fun cachedMeanSeaLevel(latitude: Double, longitude: Double, ellipsoidMeters: Double): Double? {
        val input = location(latitude, longitude, ellipsoidMeters)
        return if (converter.tryAddMslAltitudeToLocation(input) && input.hasMslAltitude()) {
            input.mslAltitudeMeters
        } else null
    }

    override fun loadMeanSeaLevel(latitude: Double, longitude: Double, ellipsoidMeters: Double): Double? {
        val appContext = context() ?: return null
        val input = location(latitude, longitude, ellipsoidMeters)
        converter.addMslAltitudeToLocation(appContext, input)
        return if (input.hasMslAltitude()) input.mslAltitudeMeters else null
    }

    private fun location(latitude: Double, longitude: Double, ellipsoidMeters: Double): Location =
        Location("gps").also { input ->
            input.latitude = latitude
            input.longitude = longitude
            input.altitude = ellipsoidMeters
        }
}
