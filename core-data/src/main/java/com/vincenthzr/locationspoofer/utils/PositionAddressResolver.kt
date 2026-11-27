package com.vincenthzr.locationspoofer.utils

import com.vincenthzr.locationspoofer.utils.CoordinateUtils.LatLng
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Text returned by online POI search, associated with the selected GCJ-02 point. */
data class SelectedPoiAddress(val point: LatLng, val name: String, val address: String)

internal data class PositionAddress(
    val province: String = "",
    val city: String = "",
    val district: String = "",
    val street: String = "",
    val streetNum: String = "",
    val address: String = "",
    val country: String = "",
    val poiName: String = ""
) {
    fun withSelectedPoi(point: LatLng, selected: SelectedPoiAddress?): PositionAddress {
        if (selected == null || !samePoint(point, selected.point)) return this
        return copy(
            address = selected.address.ifBlank { address },
            poiName = selected.name.ifBlank { poiName }
        )
    }
}

internal class PositionAddressResolver(
    private val reverseGeocode: (LatLng) -> PositionAddress?,
    private val isNearby: (LatLng, LatLng) -> Boolean
) {
    private data class Lookup(val point: LatLng, val address: PositionAddress? = null)
    private val latest = AtomicReference<Lookup?>()

    fun resolve(point: LatLng, reuseNearby: Boolean, ensureCurrent: () -> Unit = {}): PositionAddress {
        ensureCurrent()
        val prior = latest.get()
        if (prior?.address != null && (samePoint(prior.point, point) ||
                (reuseNearby && isNearby(prior.point, point)))) {
            return prior.address
        }

        ensureCurrent()
        val lookup = Lookup(point)
        val reserved = latest.compareAndSet(prior, lookup)
        val address = try {
            reverseGeocode(point) ?: PositionAddress()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            PositionAddress()
        }

        // A delayed lookup may provide its own result, but cannot update a newer lookup's cache.
        ensureCurrent()
        if (reserved) latest.compareAndSet(lookup, Lookup(point, address))
        return address
    }
}

internal suspend fun Mutex.writeCurrentPosition(action: () -> Boolean): Boolean = withLock {
    currentCoroutineContext().ensureActive()
    action()
}

private fun samePoint(first: LatLng, second: LatLng): Boolean =
    abs(first.lat - second.lat) <= 0.000001 && abs(first.lng - second.lng) <= 0.000001
