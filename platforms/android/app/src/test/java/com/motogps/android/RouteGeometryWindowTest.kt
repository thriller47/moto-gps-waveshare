package com.motogps.android

import com.motogps.android.jni.GeoPointE6
import com.motogps.android.jni.MotoBLERouteGeometry
import com.motogps.android.viewmodel.RouteGeometryWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RouteGeometryWindowTest {
    private fun route(points: List<GeoPointE6>) = MotoBLERouteGeometry(
        routeToken = 77L,
        routeGeneration = 1L,
        totalPointCount = points.size,
        viewOrigin = points.first(),
        points = points
    )

    @Test
    fun denseGeometryPreservesTurnAndContinuesPastIt() {
        // 70m north, then 70m east, sampled at ~1m intervals.
        val baseLat = 45_000_000
        val baseLon = 8_000_000
        val points = (0..70).map { GeoPointE6(baseLat + it * 9, baseLon) } +
            (1..70).map { GeoPointE6(baseLat + 70 * 9, baseLon + it * 13) }
        val full = route(points)
        val view = RouteGeometryWindow.around(full, 45.0, 8.0)
        assertTrue(view.points.size in 3..24)
        assertEquals(GeoPointE6(baseLat, baseLon), view.viewOrigin)
        assertTrue(view.points.any { abs(it.latitudeE6 - (baseLat + 70 * 9)) <= 12 && abs(it.longitudeE6 - baseLon) <= 15 })
        assertTrue(view.points.last().longitudeE6 > baseLon + 400)
        assertEquals(points.size, full.points.size)
    }

    @Test
    fun movingOriginAdvancesAlongWholeRouteNotOnlyFirst24Points() {
        val points = (0..1000).map { GeoPointE6(45_000_000 + it * 18, 8_000_000) }
        val full = route(points)
        val first = RouteGeometryWindow.around(full, 45.0, 8.0)
        val later = RouteGeometryWindow.around(full, 45.006, 8.0)
        assertTrue(first.points.size in 2..24)
        assertTrue(later.points.size in 2..24)
        assertTrue(later.points.first().latitudeE6 > first.points.first().latitudeE6 + 4000)
        assertEquals(45_006_000, later.viewOrigin.latitudeE6)
        assertEquals(points.size, full.points.size)
    }

    @Test
    fun sparseRouteKeepsEveryGatewayVertex() {
        val points = listOf(
            GeoPointE6(45_000_000, 8_000_000),
            GeoPointE6(45_000_100, 8_000_000),
            GeoPointE6(45_000_200, 8_000_020),
            GeoPointE6(45_000_300, 8_000_030),
            GeoPointE6(45_000_450, 8_000_040)
        )
        val window = RouteGeometryWindow.around(route(points), 45.0, 8.0)
        assertEquals(points, window.points)
    }

    @Test
    fun neverExceedsEsp32CapacityEvenOnZigzagRoad() {
        val points = (0..300).map {
            GeoPointE6(45_000_000 + it * 14, 8_000_000 + if (it % 2 == 0) 0 else 80)
        }
        val view = RouteGeometryWindow.around(route(points), 45.0, 8.0)
        assertTrue(view.points.size in 2..24)
        assertEquals(view.points.size, view.totalPointCount)
        assertEquals(1, view.chunkCount)
    }
}
