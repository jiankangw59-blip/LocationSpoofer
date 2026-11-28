package com.vincenthzr.locationspoofer.ui.screen

import org.junit.Assert.*
import org.junit.Test

class PointMapGestureTest {
    @Test fun initializationTapAndProgrammaticAnimationAreNotSelections() {
        val gate = PointMapGesture()
        assertFalse(gate.move()); assertFalse(gate.idle(1.0, 2.0))
        gate.down(); assertFalse(gate.move()); assertNull(gate.up()); assertFalse(gate.idle(3.0, 4.0))
    }
    @Test fun releaseBeforeCameraIdleResolvesOnlyTheFlingDestination() {
        val gate = PointMapGesture()
        gate.down(); gate.drag(); assertTrue(gate.move()); assertNull(gate.up())
        assertTrue(gate.move()); assertTrue(gate.idle(9.0, 8.0)); assertFalse(gate.idle(9.0, 8.0))
    }
    @Test fun holdingStillThenReleasingConsumesLastSettledCameraOnly() {
        val gate = PointMapGesture()
        gate.down(); gate.drag(); assertFalse(gate.idle(1.0, 1.0))
        assertTrue(gate.move()); assertFalse(gate.idle(2.0, 2.0))
        assertEquals(2.0 to 2.0, gate.up()); assertFalse(gate.idle(2.0, 2.0))
        gate.down(); gate.drag(); gate.reset(); assertFalse(gate.idle(9.0, 9.0))
    }
}
