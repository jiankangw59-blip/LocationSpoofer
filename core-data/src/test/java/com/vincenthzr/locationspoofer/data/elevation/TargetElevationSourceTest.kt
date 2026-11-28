package com.vincenthzr.locationspoofer.data.elevation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TargetElevationSourceTest {
    @Test fun keysQuantizeTheSentPointAndHandlePolesDateLineAndSignedZero() {
        assertEquals("52.5200,13.4100", TargetElevationSource.key(52.519999, 13.410004))
        assertEquals("0.0000,0.0000", TargetElevationSource.key(-0.00001, -0.0))
        assertEquals("90.0000,-180.0000", TargetElevationSource.key(90.0, 180.0))
        assertEquals(TargetElevationSource.key(-90.0, -180.0), TargetElevationSource.key(-90.0, 179.99999))
        for (lat in listOf(-90.1, 90.1, Double.NaN, Double.POSITIVE_INFINITY)) assertNull(TargetElevationSource.key(lat, 0.0))
        for (lon in listOf(-180.1,180.1,Double.NaN)) assertNull(TargetElevationSource.key(0.0,lon))
    }

    @Test fun acceptsNumericSeaSurfaceAndBelowSeaLevelTerrain() {
        assertEquals(38.0, TargetElevationSource.parseSurface("""{"elevation":[38.0]}""")!!, 0.0)
        assertEquals(-427.0, TargetElevationSource.parseSurface("""{"elevation":[-427.0]}""")!!, 0.0)
        assertEquals(0.0, TargetElevationSource.parseSurface("""{"elevation":[0]}""")!!, 0.0)
    }

    @Test fun missingBadOrBatchSamplesNeverBecomeSeaLevel() {
        for (data in listOf("{}", "[]", "null", "garbage", """{"error":true,"elevation":[1]}"""))
            assertNull(data, TargetElevationSource.parseSurface(data))
        for (values in listOf("null", "[]", "[null]", "[nan]", "[NaN]", "[1e999]", "[1,2]", "[{}]", """["0"]""", "[-999]", "[-32768]"))
            assertNull(values, TargetElevationSource.parseSurface("""{"elevation":$values}"""))
    }

    @Test fun inverseProjectionUsesTheCorrectSignForPositiveAndNegativeGeoidSeparation() {
        assertEquals(TargetElevation(38.0, 82.0), TargetElevationSource.convert(38.0, -44.0))
        assertEquals(TargetElevation(-427.0, -445.0), TargetElevationSource.convert(-427.0, 18.0))
        assertEquals(TargetElevation(0.0,-30.0), TargetElevationSource.convert(0.0,30.0))
        assertEquals(TargetElevation(0.0,0.0), TargetElevationSource.convert(0.0,0.0))
        assertNull(TargetElevationSource.convert(Double.NaN,0.0))
        assertNull(TargetElevationSource.convert(0.0, -999.0))
        assertNull(TargetElevationSource.convert(0.0,Double.NaN))
    }
}
