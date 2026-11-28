package com.vincenthzr.locationspoofer.data.model

import com.vincenthzr.locationspoofer.data.elevation.TargetElevationSource
import com.vincenthzr.locationspoofer.utils.CoordinateUtils
import org.junit.Assert.*
import org.junit.Test

class PointHeightTargetTest {
    @Test fun mainlandSelectionUsesTheSameWgs84PointAsTheLocator() {
        val gcj = CoordinateUtils.wgs84ToGcj02(31.2304,121.4737)
        val target = pointHeightTarget(gcj.lat,gcj.lng)!!
        assertEquals(TargetElevationSource.key(31.2304,121.4737),target.key)
        assertNotEquals(TargetElevationSource.key(gcj.lat,gcj.lng),target.key)
        assertEquals("52.5200,13.4100",pointHeightTarget(52.52,13.41)!!.key)
        assertNull(pointHeightTarget(null,0.0)); assertNull(pointHeightTarget(91.0,0.0))
    }
    @Test fun changedTargetOrManualModeNeverShowsThePreviousHeight() {
        val ready = PointHeightUi.Ready(38.0,77.0,false)
        val state = AppState(latitudeInput="52.52", longitudeInput="13.41", pointHeight=ready,
            pointHeightKey=pointHeightTarget(52.52,13.41)!!.key)
        assertEquals(ready,state.pointHeightForCurrentTarget)
        assertSame(PointHeightUi.Idle,state.copy(latitudeInput="31.50",longitudeInput="35.50").pointHeightForCurrentTarget)
        assertSame(PointHeightUi.Idle,state.copy(pointHeightAutomatic=false).pointHeightForCurrentTarget)
    }
}
