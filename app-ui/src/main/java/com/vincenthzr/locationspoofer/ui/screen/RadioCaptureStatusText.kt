package com.vincenthzr.locationspoofer.ui.screen

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vincenthzr.locationspoofer.data.model.RadioCaptureReport
import com.vincenthzr.locationspoofer.ui.R

@Composable
internal fun RadioCaptureStatusText(wifi: RadioCaptureReport?, cell: RadioCaptureReport?) {
    Text(stringResource(R.string.obs_wifi_result, reportText(wifi)), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.obs_cell_result, reportText(cell)), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun reportText(report: RadioCaptureReport?): String {
    val labels = radioCaptureLabels(report)
    val data = stringResource(labels.data)
    val refresh = labels.refresh ?: return data
    return stringResource(R.string.refresh_and_data, stringResource(refresh), data)
}
