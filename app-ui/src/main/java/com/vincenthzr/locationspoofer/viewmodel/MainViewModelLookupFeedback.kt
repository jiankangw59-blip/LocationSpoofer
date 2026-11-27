package com.vincenthzr.locationspoofer.viewmodel

import androidx.annotation.StringRes
import com.vincenthzr.locationspoofer.data.model.LocationLookupFeedback
import com.vincenthzr.locationspoofer.data.model.LocationLookupLine
import com.vincenthzr.locationspoofer.data.model.LocationLookupSource
import com.vincenthzr.locationspoofer.data.model.LocationLookupSource.*
import com.vincenthzr.locationspoofer.data.model.RemoteWifiMetadataStatus
import com.vincenthzr.locationspoofer.data.repository.WifiRepository
import com.vincenthzr.locationspoofer.ui.R
import com.vincenthzr.locationspoofer.utils.WigleWifiLookupResult
import kotlinx.coroutines.flow.update
import org.json.JSONArray

internal fun MainViewModel.setLocationLookupVisible(visible: Boolean) {
    _uiState.update {
        it.copy(
            locationLookupVisible = visible,
            locationLookupFeedback = if (visible) it.locationLookupFeedback else it.locationLookupFeedback?.dismiss()
        )
    }
}

internal fun MainViewModel.dismissLocationLookup() {
    _uiState.update { it.copy(locationLookupFeedback = it.locationLookupFeedback?.dismiss()) }
}

internal fun MainViewModel.discardLocationLookupForNewPoint(lat: Double?, lng: Double?) {
    _uiState.update { state ->
        if (state.locationLookupFeedback?.matchesPoint(lat, lng) == true) state
        else state.copy(locationLookupFeedback = null)
    }
}

internal fun MainViewModel.beginLocationLookup(
    lat: Double,
    lng: Double,
    wifiEnabled: Boolean,
    cellEnabled: Boolean,
    explicitlyStarted: Boolean = false
): LocationLookupFeedback {
    val lookup = LocationLookupFeedback(lat, lng, mapOf(
        WIFI to LocationLookupLine(if (wifiEnabled) R.string.lookup_wifi_pending else R.string.lookup_wifi_disabled),
        CELL to LocationLookupLine(if (cellEnabled) R.string.lookup_cell_waiting else R.string.lookup_cell_disabled),
        BLUETOOTH to LocationLookupLine(R.string.lookup_bluetooth_pending)
    ))
    _uiState.update { state ->
        val previous = state.locationLookupFeedback
        if (!state.locationLookupVisible || _spoofingUiState.value.isSearchActive ||
            previous?.blocksRepeat(lat, lng, explicitlyStarted) == true) {
            state
        } else {
            state.copy(locationLookupFeedback = lookup)
        }
    }
    return lookup
}

internal fun MainViewModel.reportLocationLookup(
    lookup: LocationLookupFeedback?,
    source: LocationLookupSource,
    @StringRes text: Int,
    vararg arguments: Int
) {
    reportLocationLookup(lookup, source, LocationLookupLine(text, arguments.toList()))
}

internal fun MainViewModel.reportLocationLookup(
    lookup: LocationLookupFeedback?,
    source: LocationLookupSource,
    line: LocationLookupLine
) {
    if (lookup == null) return
    _uiState.update { state ->
        val current = state.locationLookupFeedback ?: return@update state
        if (!state.locationLookupVisible || !current.matchesPoint(
                state.latitudeInput.toDoubleOrNull(), state.longitudeInput.toDoubleOrNull())) return@update state
        state.copy(locationLookupFeedback = current.updateLine(lookup, source, line))
    }
}

internal fun MainViewModel.finishLocationLookup(
    lookup: LocationLookupFeedback?,
    reusedWifi: Boolean,
    reusedCells: Boolean
) {
    if (lookup == null) return
    val state = _uiState.value
    if (reusedWifi) {
        if (state.wifiApCount > 0) reportLocationLookup(lookup, WIFI, R.string.lookup_wifi_local, state.wifiApCount)
        else reportLocationLookup(lookup, WIFI, R.string.lookup_wifi_local_empty)
    }
    if (reusedCells) {
        val count = countRecords(state.collectedCellJson)
        if (count > 0) reportLocationLookup(lookup, CELL, R.string.lookup_cell_local, count)
        else reportLocationLookup(lookup, CELL, R.string.lookup_cell_local_empty)
    }
    val bluetoothCount = countRecords(state.collectedBluetoothJson)
    if (bluetoothCount > 0) {
        reportLocationLookup(lookup, BLUETOOTH, R.string.lookup_bluetooth_local, bluetoothCount)
    } else {
        reportLocationLookup(lookup, BLUETOOTH, R.string.lookup_bluetooth_unavailable)
    }
}

internal fun appleWifiLine(count: Int, reusedPreview: Boolean = false): LocationLookupLine =
    LocationLookupLine(if (reusedPreview) R.string.lookup_wifi_preview else R.string.lookup_wifi_count, listOf(count))

internal fun appleMetadataLine(result: WifiRepository.AppleWifiImportResult): LocationLookupLine =
    when (result.metadataStatus) {
        RemoteWifiMetadataStatus.NOT_REQUESTED -> LocationLookupLine(R.string.lookup_metadata_not_requested)
        RemoteWifiMetadataStatus.NOT_CONFIGURED -> LocationLookupLine(R.string.lookup_metadata_no_key)
        RemoteWifiMetadataStatus.MATCHED -> LocationLookupLine(
            R.string.lookup_metadata_count, listOf(result.metadataCount, result.ssidCount, result.frequencyCount))
        RemoteWifiMetadataStatus.NO_MATCH -> LocationLookupLine(R.string.lookup_metadata_no_match)
        RemoteWifiMetadataStatus.UNAVAILABLE -> when (val http = result.metadataHttpStatus) {
            401, 403 -> LocationLookupLine(R.string.remote_wifi_metadata_auth, listOf(checkNotNull(http)))
            429 -> LocationLookupLine(R.string.lookup_metadata_rate_limit)
            null -> LocationLookupLine(R.string.lookup_metadata_failed)
            else -> LocationLookupLine(R.string.lookup_metadata_http, listOf(http))
        }
    }.copy(fromPreview = result.reusedPreview)

internal fun wigleFallbackEmptyLine(result: WigleWifiLookupResult): LocationLookupLine = when (result) {
    is WigleWifiLookupResult.Records -> LocationLookupLine(R.string.lookup_wigle_no_data)
    is WigleWifiLookupResult.HttpFailure -> when (result.status) {
        401, 403 -> LocationLookupLine(R.string.lookup_wigle_auth, listOf(result.status))
        429 -> LocationLookupLine(R.string.lookup_wigle_rate_limit)
        else -> LocationLookupLine(R.string.lookup_wigle_http, listOf(result.status))
    }
    WigleWifiLookupResult.RequestFailed -> LocationLookupLine(R.string.lookup_wigle_request_failed)
    WigleWifiLookupResult.InvalidResponse -> LocationLookupLine(R.string.lookup_wigle_invalid_response)
}

private fun countRecords(payload: String): Int = runCatching { JSONArray(payload).length() }.getOrDefault(0)
