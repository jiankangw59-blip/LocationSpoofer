package com.vincenthzr.locationspoofer.ui.screen

import com.vincenthzr.locationspoofer.data.model.RadioCaptureReport
import com.vincenthzr.locationspoofer.ui.R
import com.vincenthzr.locationspoofer.utils.CaptureStatus
import com.vincenthzr.locationspoofer.utils.RefreshOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

class RadioCaptureLabelsTest {
    private fun labels(refresh: RefreshOutcome, data: CaptureStatus) = radioCaptureLabels(RadioCaptureReport(refresh, data))

    @Test fun noRoundIsWaitingRatherThanEmptyOrFailure() {
        assertEquals(RadioCaptureLabels(null, R.string.obs_waiting), radioCaptureLabels(null))
    }

    @Test fun timeoutCanReturnCachedDataWithoutRefreshingIt() {
        assertEquals(RadioCaptureLabels(R.string.refresh_timed_out, R.string.obs_cached),
            labels(RefreshOutcome.TIMED_OUT, CaptureStatus.CACHED))
    }

    @Test fun completionDoesNotProveFreshnessOrReadAccess() {
        for ((data, label) in listOf(CaptureStatus.NOT_UPDATED to R.string.obs_not_updated,
            CaptureStatus.PERMISSION_DENIED to R.string.obs_denied, CaptureStatus.EMPTY to R.string.obs_empty,
            CaptureStatus.OBSERVED to R.string.obs_received, CaptureStatus.FAILED to R.string.obs_failed)) {
            assertEquals(RadioCaptureLabels(R.string.refresh_completed, label), labels(RefreshOutcome.COMPLETED, data))
        }
    }

    @Test fun cachedDataDoesNotHideWhetherRefreshWasRequestedOrFailed() {
        for ((refresh, label) in listOf(RefreshOutcome.NOT_REQUESTED to R.string.refresh_not_requested,
            RefreshOutcome.LOCAL_COOLDOWN to R.string.refresh_local_cooldown,
            RefreshOutcome.NOT_CONFIRMED to R.string.refresh_not_confirmed,
            RefreshOutcome.PERMISSION_DENIED to R.string.refresh_permission_denied,
            RefreshOutcome.FAILED to R.string.refresh_failed)) {
            assertEquals(RadioCaptureLabels(label, R.string.obs_cached), labels(refresh, CaptureStatus.CACHED))
        }
    }
}
