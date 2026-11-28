package com.vincenthzr.locationspoofer.ui.screen

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vincenthzr.locationspoofer.ui.R
import com.vincenthzr.locationspoofer.utils.BleScanStatus

@Composable
internal fun BleObservationStatusText(status: BleScanStatus?) {
    val message = when (status) {
        null -> R.string.ble_observation_waiting
        BleScanStatus.OBSERVED -> R.string.ble_observation_received
        BleScanStatus.EMPTY -> R.string.ble_observation_empty
        BleScanStatus.ADAPTER_OFF -> R.string.ble_observation_off
        BleScanStatus.PERMISSION_DENIED -> R.string.ble_observation_denied
        BleScanStatus.UNAVAILABLE -> R.string.ble_observation_unavailable
        BleScanStatus.FAILED -> R.string.ble_observation_failed
    }
    Text(stringResource(message), style = MaterialTheme.typography.bodySmall)
}
