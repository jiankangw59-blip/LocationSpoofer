package com.vincenthzr.locationspoofer.utils

import com.vincenthzr.locationspoofer.utils.CoordinateUtils.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutePathTest {

    private val a = LatLng(30.0, 120.0)
    private val b = GeoMath.destination(a, 90.0, 1000.0) // 正东 1000 米

    @Test
    fun `destination and distance are consistent`() {
        assertEquals(1000.0, GeoMath.distance(a, b), 0.01)
        assertEquals(90.0, GeoMath.bearing(a, b).toDouble(), 0.01)
    }

    @Test
    fun `open route goes back and forth`() {
        val path = RoutePath(listOf(a, b))
        assertFalse(path.isClosedLoop)
        val out = path.positionAt(400.0, stopAtDestination = false)
        assertEquals(400.0, GeoMath.distance(a, LatLng(out.lat, out.lng)), 0.5)
        assertEquals(90.0, out.bearing.toDouble(), 0.5)
        val back = path.positionAt(1600.0, stopAtDestination = false) // 折返途中，距起点 400 米
        assertEquals(400.0, GeoMath.distance(a, LatLng(back.lat, back.lng)), 0.5)
        assertEquals(270.0, back.bearing.toDouble(), 0.5)
    }

    @Test
    fun `crossing the date line uses the short segment in both directions`() {
        for (sign in listOf(-1.0, 1.0)) {
            val start = LatLng(0.0, sign * 179.999)
            val end = LatLng(0.0, -start.lng)
            val path = RoutePath(listOf(start, end))
            assertEquals(222.63898, path.totalDistance, 0.001)
            for (fraction in listOf(0.25, 0.5, 0.75)) {
                for (phase in listOf(fraction, 2.0 - fraction)) {
                    val pos = path.positionAt(path.totalDistance * phase, false)
                    assertTrue(pos.lng in -180.0..180.0)
                    assertEquals(path.totalDistance * fraction,
                        GeoMath.distance(start, LatLng(pos.lat, pos.lng)), 0.001)
                    val expected = if ((sign > 0.0) == (phase < 1.0)) 90.0 else 270.0
                    assertEquals(expected, pos.bearing.toDouble(), 0.001)
                }
            }
            assertEquals(end.lng, path.positionAt(path.totalDistance, false).lng, 0.0)
        }
    }

    @Test
    fun `manual movement wraps longitude and equivalent date line points coincide`() {
        for (sign in listOf(-1.0, 1.0)) {
            val end = GeoMath.destination(LatLng(0.0, sign * 179.999),
                if (sign > 0.0) 90.0 else 270.0, 222.63898)
            assertEquals(-sign * 179.999, end.lng, 1e-8)
        }
        val same = RoutePath(listOf(LatLng(4.0, 180.0), LatLng(4.0, -180.0)))
        assertEquals(0.0, same.totalDistance, 0.0)
        assertFalse(same.isValid)
    }

    @Test
    fun `repeated waypoints do not invent a north heading at departure or arrival`() {
        val corner = LatLng(0.001, 0.0)
        val end = LatLng(0.001, 0.001)
        val simple = RoutePath(listOf(end, corner, LatLng(0.002, 0.0)))
        val points = simple.points.flatMap { listOf(it, it, it) }
        val path = RoutePath(points)
        assertEquals(points, path.points) // Do not discard saved waypoints.
        for (fraction in listOf(0.0, 0.25, 0.75, 1.0, 1.5, 2.0)) {
            for (stop in listOf(true, false)) {
                val distance = simple.totalDistance * fraction
                assertEquals(simple.positionAt(distance, stop), path.positionAt(distance, stop))
            }
        }
        val east = RoutePath(listOf(corner, corner, end, end))
        assertEquals(90.0, east.positionAt(0.0, false).bearing.toDouble(), 0.01)
        assertEquals(90.0, east.positionAt(east.totalDistance, true).bearing.toDouble(), 0.01)
    }

    @Test
    fun `closed loop keeps going forward`() {
        val c = GeoMath.destination(b, 0.0, 1000.0)
        val path = RoutePath(listOf(a, b, c, a))
        assertTrue(path.isClosedLoop)
        val lap = path.positionAt(path.totalDistance + 300.0, stopAtDestination = false)
        assertEquals(300.0, GeoMath.distance(a, LatLng(lap.lat, lap.lng)), 0.5)
    }

    @Test
    fun `stops at the destination when requested`() {
        val path = RoutePath(listOf(a, b))
        val end = path.positionAt(5000.0, stopAtDestination = true)
        assertTrue(end.arrived)
        assertEquals(b.lat, end.lat, 1e-9)
        assertEquals(b.lng, end.lng, 1e-9)
    }
}
