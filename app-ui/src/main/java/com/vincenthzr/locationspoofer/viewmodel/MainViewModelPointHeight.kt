package com.vincenthzr.locationspoofer.viewmodel

import android.widget.Toast
import com.vincenthzr.locationspoofer.data.elevation.TargetElevation
import com.vincenthzr.locationspoofer.data.elevation.TargetElevationSource
import com.vincenthzr.locationspoofer.data.model.PointHeightTarget
import com.vincenthzr.locationspoofer.data.model.PointHeightUi
import com.vincenthzr.locationspoofer.data.model.pointHeightTarget
import com.vincenthzr.locationspoofer.ui.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

internal fun MainViewModel.setPointHeightAutomatic(enabled: Boolean) {
    if (_uiState.value.pointHeightAutomatic == enabled) return
    discardPendingPointHeight()
    pointConfigurationJob?.cancel()
    settingsRepository.pointHeightAutomatic = enabled
    _uiState.update { it.copy(pointHeightAutomatic = enabled) }
    if (enabled) schedulePointHeightForSelection()
}

internal fun MainViewModel.discardPendingPointHeight() {
    lastExplicitHeightKey = null
    pointHeightRequests.invalidate()
    _uiState.update { it.copy(pointHeight = PointHeightUi.Idle, pointHeightKey = null) }
}

private fun MainViewModel.selectedHeightTarget(): PointHeightTarget? {
    val state = _uiState.value
    if (!state.pointHeightAutomatic) return null
    return pointHeightTarget(state.latitudeInput.toDoubleOrNull(), state.longitudeInput.toDoubleOrNull())
}

// Deliberately wired only to user-selected stationary points, never to location listeners or route ticks.
internal fun MainViewModel.schedulePointHeightForSelection() {
    val target = selectedHeightTarget() ?: return discardPendingPointHeight()
    lastExplicitHeightKey = target.key
    if (_uiState.value.pointHeightKey != target.key)
        _uiState.update { it.copy(pointHeightKey = target.key, pointHeight = PointHeightUi.Loading) }
    pointHeightRequests.select(target.key) { loadPointHeight(target) }
}

internal fun MainViewModel.wasPointHeightSelected(lat: Double, lng: Double): Boolean {
    val current = lastExplicitHeightKey ?: return false
    return _uiState.value.pointHeightAutomatic && pointHeightTarget(lat, lng)?.key == current
}

internal fun MainViewModel.notifyPointHeightUnavailable() {
    Toast.makeText(context, R.string.point_height_unavailable, Toast.LENGTH_LONG).show()
}

internal suspend fun MainViewModel.previewPointHeight() {
    val target = selectedHeightTarget() ?: return
    pointHeightRequests.await(target.key) { loadPointHeight(target) }
}

internal suspend fun MainViewModel.resolvePointHeight(lat: Double, lon: Double): TargetElevation? {
    // Read the normalized UI target, matching how the coordinate request was prepared.
    if (!configurationTargetMatches(lat, lon)) return null
    val target = selectedHeightTarget() ?: return null
    return pointHeightRequests.await(target.key) { loadPointHeight(target) }
}

private suspend fun MainViewModel.loadPointHeight(target: PointHeightTarget): TargetElevation? {
    fun wanted() = selectedHeightTarget()?.key == target.key
    if (!wanted()) return null
    _uiState.update { it.copy(pointHeightKey = target.key, pointHeight = PointHeightUi.Loading) }
    val cached = withContext(Dispatchers.IO) { pointHeightCache.get(target.key) }
    val value = cached ?: TargetElevationSource.lookup(context, target.wgs.lat, target.wgs.lng)
    currentCoroutineContext().ensureActive()
    if (!wanted()) return null
    if (cached == null && value != null) withContext(Dispatchers.IO) { pointHeightCache.put(target.key, value) }
    currentCoroutineContext().ensureActive()
    if (!wanted()) return null
    _uiState.update { it.copy(pointHeightKey = target.key, pointHeight = if (value == null)
        PointHeightUi.Unavailable else PointHeightUi.Ready(value.surfaceMslMeters, value.ellipsoidMeters, cached != null)) }
    return value
}
