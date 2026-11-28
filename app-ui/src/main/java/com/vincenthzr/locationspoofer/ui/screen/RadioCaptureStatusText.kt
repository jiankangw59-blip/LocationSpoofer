package com.vincenthzr.locationspoofer.ui.screen

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vincenthzr.locationspoofer.ui.R
import com.vincenthzr.locationspoofer.utils.CaptureStatus

@Composable
internal fun RadioCaptureStatusText(wifi: CaptureStatus?, cell: CaptureStatus?) {
    Text(stringResource(R.string.obs_wifi_result, captureStatusText(wifi)), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.obs_cell_result, captureStatusText(cell)), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun captureStatusText(status: CaptureStatus?): String = stringResource(when (status) {
    null -> R.string.obs_waiting
    CaptureStatus.OBSERVED -> R.string.obs_received
    CaptureStatus.EMPTY -> R.string.obs_empty
    CaptureStatus.CACHED -> R.string.obs_cached
    CaptureStatus.NOT_UPDATED -> R.string.obs_not_updated
    CaptureStatus.PERMISSION_DENIED -> R.string.obs_denied
    CaptureStatus.FAILED -> R.string.obs_failed
})
