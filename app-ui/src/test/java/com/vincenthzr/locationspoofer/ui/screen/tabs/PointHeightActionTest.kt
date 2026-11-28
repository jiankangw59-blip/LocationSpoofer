package com.vincenthzr.locationspoofer.ui.screen.tabs

import com.vincenthzr.locationspoofer.data.model.PointHeightUi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PointHeightActionTest {
    private val target = "35.1" to "-119.8"
    private val start = PointHeightAction(target, startWhenReady = true)
    private val ready = PointHeightUi.Ready(110.0, 79.5, false)

    @Test fun onlyExplicitStartCanLaunchAfterLookup() {
        assertTrue(start.mayStart(target, true, true, ready))
        assertFalse(start.copy(startWhenReady = false).mayStart(target, true, true, ready))
    }

    @Test fun changingTargetModeOrClosingDialogCancelsPendingStart() {
        assertFalse(start.mayStart("35.2" to "-119.8", true, true, ready))
        assertFalse(start.mayStart("35.1" to "-119.7", true, true, ready))
        assertFalse(start.mayStart(target, false, true, ready))
        assertFalse(start.mayStart(target, true, false, ready))
    }

    @Test fun absentPendingAndFailedResultsNeverStart() {
        for (notReady in listOf(PointHeightUi.Idle, PointHeightUi.Loading, PointHeightUi.Unavailable)) {
            assertFalse(start.mayStart(target, true, true, notReady))
        }
    }
}
