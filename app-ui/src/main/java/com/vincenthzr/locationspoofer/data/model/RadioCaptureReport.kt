package com.vincenthzr.locationspoofer.data.model

import com.vincenthzr.locationspoofer.utils.CaptureStatus
import com.vincenthzr.locationspoofer.utils.RefreshOutcome

/** The request and returned data belong to the same saved round; neither explains the other. */
data class RadioCaptureReport(val refresh: RefreshOutcome, val data: CaptureStatus)
