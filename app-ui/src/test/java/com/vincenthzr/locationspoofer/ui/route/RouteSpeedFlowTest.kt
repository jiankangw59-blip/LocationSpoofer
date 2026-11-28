package com.vincenthzr.locationspoofer.ui.route

import com.vincenthzr.locationspoofer.ui.screen.tabs.route.displaySpeeds
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class RouteSpeedFlowTest {
    @Test fun onlyDisplayChangesAreEmittedEvenWhenTheRouteKeepsMoving() = runBlocking {
        val speeds = listOf(1.40, 1.42, 1.44, 1.46, 1.49, 2.70, 2.71, 0.0, 0.0)
        val positions = speeds.mapIndexed { i, speed -> i to speed }
        assertEquals(listOf(1.4, 1.5, 2.7, 0.0), positions.asFlow().displaySpeeds { it.second }.toList())
    }
}
