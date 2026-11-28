package com.vincenthzr.locationspoofer.viewmodel

import android.widget.Toast
import com.vincenthzr.locationspoofer.data.elevation.TargetElevation
import com.vincenthzr.locationspoofer.data.elevation.TargetElevationSource
import com.vincenthzr.locationspoofer.data.model.PointHeightUi
import com.vincenthzr.locationspoofer.data.model.pointHeightTarget
import com.vincenthzr.locationspoofer.ui.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal fun MainViewModel.setPointHeightAutomatic(enabled: Boolean) {
    if (_uiState.value.pointHeightAutomatic == enabled) return
    pointHeightRequest++
    pointConfigurationJob?.cancel()
    settingsRepository.pointHeightAutomatic = enabled
    _uiState.update { it.copy(pointHeightAutomatic = enabled, pointHeight = PointHeightUi.Idle, pointHeightKey = null) }
}

internal fun MainViewModel.notifyPointHeightUnavailable() {
    Toast.makeText(context, R.string.point_height_unavailable, Toast.LENGTH_LONG).show()
}

internal suspend fun MainViewModel.previewPointHeight() {
    val state = _uiState.value
    val lat = state.latitudeInput.toDoubleOrNull() ?: return
    val lon = state.longitudeInput.toDoubleOrNull() ?: return
    if (state.pointHeightAutomatic) resolvePointHeight(lat, lon)
}

// Called on Main; only the disk, network and geoid lookups leave the UI thread.
// Bind every publication to the current target, mode and request generation.
internal suspend fun MainViewModel.resolvePointHeight(lat: Double, lon: Double): TargetElevation? {
    val target = pointHeightTarget(lat, lon) ?: return null
    fun wanted() = _uiState.value.pointHeightAutomatic && configurationTargetMatches(lat, lon)
    if (!wanted()) return null
    val seq = ++pointHeightRequest
    return pointHeightMutex.withLock {
        if (seq != pointHeightRequest || !wanted()) return@withLock null
        _uiState.update { it.copy(pointHeightKey = target.key, pointHeight = PointHeightUi.Loading) }
        try {
            val cached = withContext(Dispatchers.IO) { pointHeightCache.get(target.key) }
            val value = cached ?: TargetElevationSource.lookup(context, target.wgs.lat, target.wgs.lng)
            currentCoroutineContext().ensureActive()
            if (seq != pointHeightRequest || !wanted()) return@withLock null
            if (cached == null && value != null) withContext(Dispatchers.IO) { pointHeightCache.put(target.key, value) }
            currentCoroutineContext().ensureActive()
            if (seq != pointHeightRequest || !wanted()) return@withLock null
            _uiState.update { it.copy(pointHeightKey = target.key, pointHeight = if (value == null)
                PointHeightUi.Unavailable else PointHeightUi.Ready(value.surfaceMslMeters, value.ellipsoidMeters, cached != null)) }
            value
        } finally {
            if (seq == pointHeightRequest && _uiState.value.pointHeight === PointHeightUi.Loading)
                _uiState.update { it.copy(pointHeightKey = null, pointHeight = PointHeightUi.Idle) }
        }
    }
}
