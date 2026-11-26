package com.vincenthzr.locationspoofer.xposed.hooks

import java.io.IOException
import java.util.concurrent.locks.ReentrantLock

internal data class NmeaAltitude(val meanSeaLevelMeters: Double, val geoidSeparationMeters: Double)

internal interface NmeaAltitudeModel {
    fun cachedMeanSeaLevel(latitude: Double, longitude: Double, ellipsoidMeters: Double): Double?
    fun loadMeanSeaLevel(latitude: Double, longitude: Double, ellipsoidMeters: Double): Double?
}

internal class NmeaAltitudeResolver(
    private val model: NmeaAltitudeModel,
    private val onWarmResult: (Boolean) -> Unit = {}
) {
    private val modelLock = ReentrantLock()
    private var attemptedGeneration: Any? = null
    private var modelUnavailable = false

    fun cached(latitude: Double, longitude: Double, ellipsoidMeters: Double): NmeaAltitude? =
        resolve(null, latitude, longitude, ellipsoidMeters)

    /** Only call from the existing NMEA worker. The configuration object is an identity token. */
    fun resolveOnWorker(
        configGeneration: Any,
        latitude: Double,
        longitude: Double,
        ellipsoidMeters: Double
    ): NmeaAltitude? = resolve(configGeneration, latitude, longitude, ellipsoidMeters)

    private fun resolve(
        configGeneration: Any?,
        latitude: Double,
        longitude: Double,
        ellipsoidMeters: Double
    ): NmeaAltitude? {
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0 || !ellipsoidMeters.isFinite()) {
            return null
        }
        // Registration may overlap a worker reading model assets. Return unknown immediately.
        if (!modelLock.tryLock()) return null
        var warmResult: Boolean? = null
        try {
            if (modelUnavailable) return null
            val msl = (model.cachedMeanSeaLevel(latitude, longitude, ellipsoidMeters) ?: run {
                if (configGeneration == null || attemptedGeneration === configGeneration) return null
                attemptedGeneration = configGeneration
                warmResult = false
                model.loadMeanSeaLevel(latitude, longitude, ellipsoidMeters)
            }) ?: return null
            val separation = ellipsoidMeters - msl
            if (!separation.isFinite()) return null
            if (warmResult != null) warmResult = true
            return NmeaAltitude(msl, separation)
        } catch (_: IOException) {
            return null
        } catch (_: RuntimeException) {
            return null
        } catch (_: LinkageError) {
            modelUnavailable = true
            return null
        } finally {
            modelLock.unlock()
            warmResult?.let {
                try {
                    onWarmResult(it)
                } catch (_: RuntimeException) {
                    // Reporting must not affect a completed projection.
                } catch (_: LinkageError) {
                    // The diagnostic entry point may be unavailable during initialization.
                }
            }
        }
    }
}
