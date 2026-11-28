package com.vincenthzr.locationspoofer.ui.screen

import androidx.annotation.StringRes
import com.vincenthzr.locationspoofer.data.model.RadioCaptureReport
import com.vincenthzr.locationspoofer.ui.R
import com.vincenthzr.locationspoofer.utils.CaptureStatus
import com.vincenthzr.locationspoofer.utils.RefreshOutcome

data class RadioCaptureLabels(@get:StringRes val refresh: Int?, @get:StringRes val data: Int)

fun radioCaptureLabels(report: RadioCaptureReport?): RadioCaptureLabels {
    if (report == null) return RadioCaptureLabels(null, R.string.obs_waiting)
    val refresh = when (report.refresh) {
        RefreshOutcome.NOT_REQUESTED -> R.string.refresh_not_requested
        RefreshOutcome.COMPLETED -> R.string.refresh_completed
        RefreshOutcome.NOT_CONFIRMED -> R.string.refresh_not_confirmed
        RefreshOutcome.LOCAL_COOLDOWN -> R.string.refresh_local_cooldown
        RefreshOutcome.TIMED_OUT -> R.string.refresh_timed_out
        RefreshOutcome.PERMISSION_DENIED -> R.string.refresh_permission_denied
        RefreshOutcome.FAILED -> R.string.refresh_failed
    }
    val data = when (report.data) {
        CaptureStatus.OBSERVED -> R.string.obs_received
        CaptureStatus.EMPTY -> R.string.obs_empty
        CaptureStatus.CACHED -> R.string.obs_cached
        CaptureStatus.NOT_UPDATED -> R.string.obs_not_updated
        CaptureStatus.PERMISSION_DENIED -> R.string.obs_denied
        CaptureStatus.FAILED -> R.string.obs_failed
    }
    return RadioCaptureLabels(refresh, data)
}
