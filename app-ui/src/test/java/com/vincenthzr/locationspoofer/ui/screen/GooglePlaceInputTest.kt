package com.vincenthzr.locationspoofer.ui.screen

import com.vincenthzr.locationspoofer.utils.CoordinateUtils
import org.junit.Assert.assertEquals
import org.junit.Test

class GooglePlaceInputTest {
    @Test fun inRegionPlaceMatchesCameraAndReturnsToOriginalGooglePoint() {
        val lat = 39.908823
        val lng = 116.397470
        // MapCoordinateUtils delegates Google camera coordinates to this same application transform.
        val camera = CoordinateUtils.wgs84ToGcj02(lat, lng)
        val result = googlePlaceItem("A", "B", lat, lng)
        assertEquals(camera.lat, result.lat, 0.0)
        assertEquals(camera.lng, result.lng, 0.0)
        val back = CoordinateUtils.gcj02ToWgs84(result.lat, result.lng)
        assertEquals(lat, back.lat, 5e-8)
        assertEquals(lng, back.lng, 5e-8)
    }

    @Test fun outsideRegionPlaceRetainsCoordinatesAndMetadata() {
        val result = googlePlaceItem("Square", "Example", 48.86, 2.35)
        assertEquals(AppPoiItem("Square", "Example", 48.86, 2.35), result)
    }
}
