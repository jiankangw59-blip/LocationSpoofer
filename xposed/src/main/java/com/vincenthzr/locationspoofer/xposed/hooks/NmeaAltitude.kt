package com.vincenthzr.locationspoofer.xposed.hooks

import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.hypot

internal const val FINE_LOCATION_PERMISSION_LEVEL = 2

internal data class NmeaAltitude(
    val meanSeaLevelMeters: Double,
    val geoidSeparationMeters: Double,
    val modelRmseMeters: Float? = null
) {
    fun locationAccuracyMeters(verticalAccuracyMeters: Float?): Float? {
        val vertical = verticalAccuracyMeters?.takeIf { it.isFinite() && it >= 0f } ?: return null
        val modelRmse = modelRmseMeters?.takeIf { it.isFinite() && it >= 0f } ?: return null
        return hypot(vertical.toDouble(), modelRmse.toDouble()).toFloat().takeIf { it.isFinite() }
    }
}

internal data class ModelMslAltitude(val meters: Double, val rmseMeters: Float? = null)

internal interface NmeaAltitudeModel {
    fun cachedMeanSeaLevel(latitude: Double, longitude: Double, ellipsoidMeters: Double): ModelMslAltitude?
    fun loadMeanSeaLevel(latitude: Double, longitude: Double, ellipsoidMeters: Double): ModelMslAltitude?
}

internal class NmeaAltitudeResolver(
    private val model: NmeaAltitudeModel,
    private val onWarmResult: (Boolean) -> Unit = {}
) {
    private val modelLock = ReentrantLock()
    private var attemptedGeneration: Any? = null
    private var modelUnavailable = false
    @Volatile private var hasProjection = false

    fun cached(latitude: Double, longitude: Double, ellipsoidMeters: Double): NmeaAltitude? =
        resolve(null, latitude, longitude, ellipsoidMeters)

    fun cachedForLocation(
        permissionLevel: Int?,
        latitude: Double,
        longitude: Double,
        ellipsoidMeters: Double
    ): NmeaAltitude? {
        // Fine is level 2. The ROM's coarse filter only clears the separate ellipsoid flag.
        if (permissionLevel != FINE_LOCATION_PERMISSION_LEVEL || !hasProjection) return null
        return cached(latitude, longitude, ellipsoidMeters)
    }

    /** Only call from an existing worker. The configuration object is an identity token. */
    fun resolveOnWorker(
        configGeneration: Any,
        latitude: Double,
        longitude: Double,
        ellipsoidMeters: Double,
        generationIsCurrent: () -> Boolean = { true }
    ): NmeaAltitude? = resolve(configGeneration, latitude, longitude, ellipsoidMeters, generationIsCurrent)

    private fun resolve(
        configGeneration: Any?,
        latitude: Double,
        longitude: Double,
        ellipsoidMeters: Double,
        generationIsCurrent: (() -> Boolean)? = null
    ): NmeaAltitude? {
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0 || !ellipsoidMeters.isFinite()) {
            return null
        }
        // Registration may overlap a worker reading model assets. Return unknown immediately.
        if (!modelLock.tryLock()) return null
        var warmResult: Boolean? = null
        try {
            if (modelUnavailable || generationIsCurrent?.invoke() == false) return null
            val msl = (model.cachedMeanSeaLevel(latitude, longitude, ellipsoidMeters) ?: run {
                if (configGeneration == null || attemptedGeneration === configGeneration ||
                    generationIsCurrent?.invoke() == false) return null
                attemptedGeneration = configGeneration
                warmResult = false
                model.loadMeanSeaLevel(latitude, longitude, ellipsoidMeters)
            }) ?: return null
            val separation = ellipsoidMeters - msl.meters
            if (!separation.isFinite()) return null
            if (warmResult != null) warmResult = true
            hasProjection = true
            if (generationIsCurrent?.invoke() == false) return null
            return NmeaAltitude(msl.meters, separation, msl.rmseMeters?.takeIf { it.isFinite() && it >= 0f })
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
