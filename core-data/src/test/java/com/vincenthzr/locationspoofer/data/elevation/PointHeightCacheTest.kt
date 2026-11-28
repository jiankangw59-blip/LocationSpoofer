package com.vincenthzr.locationspoofer.data.elevation

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PointHeightCacheTest {
    @get:Rule val tmp = TemporaryFolder()
    @Test fun identicalGridAvailableOfflineButNotFromOtherTarget() {
        val dir = tmp.newFolder()
        val a = TargetElevationSource.key(52.52,13.41)!!
        val b = TargetElevationSource.key(52.53,13.41)!!
        val v = TargetElevation(38.0, 77.0)
        PointHeightCache(dir).put(a,v)
        assertEquals(v, PointHeightCache(dir).get(a))
        assertNull(PointHeightCache(dir).get(b))
    }
    @Test fun invalidFutureExpiredCorruptAndOverCapacityDoNotBecomeHits() {
        val dir = tmp.newFolder()
        var now = 1700000000000L
        val cache = PointHeightCache(dir) { now }
        val v = TargetElevation(-420.0,-400.0)
        cache.put("bad",TargetElevation(20.0,Double.NaN)); assertNull(cache.get("bad"))
        cache.put("old",v); now--; assertNull(cache.get("old"))
        now += 181L*86400000; assertNull(cache.get("old"))
        repeat(66) { now+=2000; cache.put("p$it",v) }
        assertEquals(64,dir.listFiles()!!.size); assertNull(cache.get("p0")); assertEquals(v,cache.get("p65"))
        dir.listFiles()!!.forEach { it.writeText("oops") }; assertNull(cache.get("p65"))
    }
}
