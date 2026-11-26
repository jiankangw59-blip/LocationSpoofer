package com.vincenthzr.locationspoofer.utils

import org.junit.Assert.*
import org.junit.Test

class VisibleCellsTest {
    @Test fun capsPoolAndUsesKnownDistanceRatherThanInputOrder() {
        val pool=(0..99).map { VisibleCells.Candidate("id-$it",-80,false,(100-it).toDouble()) }
        val selected=VisibleCells.select(pool)
        assertEquals(12,selected.size)
        assertEquals((99 downTo 88).toList(),selected)
        assertTrue(pool.none { it.registered })
    }
    @Test fun preservesAnExplicitServingCellOutsideTheNearestSubset() {
        val pool=(0..99).map { VisibleCells.Candidate("id-$it",-80,it==0,(100-it).toDouble()) }
        assertEquals(listOf(0)+(99 downTo 89),VisibleCells.select(pool))
    }
    @Test fun deduplicatesIdentityAndChoosesOneOfMultipleServingRecords() {
        val pool=listOf(VisibleCells.Candidate("a",-80,true,20.0),VisibleCells.Candidate("a",-70,false,10.0),
            VisibleCells.Candidate("b",-90,true,30.0))
        assertEquals(listOf(0,2),VisibleCells.select(pool))
    }
    @Test fun unknownDistancesUseSignalOrderAndStableIdentityTies() {
        val pool=listOf(VisibleCells.Candidate("z",-90,false),VisibleCells.Candidate("b",-70,false),VisibleCells.Candidate("a",-70,false))
        assertEquals(listOf(2,1,0),VisibleCells.select(pool))
    }
    @Test fun unavailableDistanceDoesNotPoisonRanking() {
        assertEquals(listOf(0),VisibleCells.select(listOf(VisibleCells.Candidate("a",-70,false,Double.NaN))))
        assertTrue(VisibleCells.select(emptyList()).isEmpty())
    }
}
