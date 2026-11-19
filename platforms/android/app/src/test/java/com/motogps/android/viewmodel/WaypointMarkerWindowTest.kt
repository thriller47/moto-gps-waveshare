package com.motogps.android.viewmodel

import com.motogps.android.jni.GeoPointE6
import com.motogps.android.jni.MotoBLERouteGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaypointMarkerWindowTest {
    private fun window() = MotoBLERouteGeometry(
        routeToken = 1, routeGeneration = 1, totalPointCount = 2,
        viewOrigin = GeoPointE6(45_000_000, 9_000_000),
        points = listOf(GeoPointE6(45_000_000, 9_000_000),
                        GeoPointE6(45_004_000, 9_000_000))
    )
    private fun stop(name: String, lat: Double, lon: Double = 9.0) =
        PlaceSearchResult(name, "", "Indirizzo", lat, lon)

    @Test fun ordinaryAndCircularStopsMapToGreenLine() {
        val list = listOf(stop("Passo", 45.002), stop("Lago", 45.003),
                          stop("Fuori zona", 46.0))
        val markers = WaypointMarkerWindow.visible(window(), list)
        assertEquals(listOf(1, 2), markers.map { it.index })
        assertEquals(45_002_000, markers[0].point.latitudeE6)
        assertEquals(1, WaypointMarkerWindow.visible(window(),
            listOf(stop("Tour circolare", 45.001))).size)
    }
    @Test fun reachedOrSkippedStopsDisappear() {
        val list = listOf(stop("Visitata", 45.001), stop("Saltata", 45.002),
                          stop("Rimane", 45.003))
        val remaining = WaypointMarkerWindow.visible(window(), list.drop(2))
        assertEquals(1, remaining.single().index)
        assertEquals(45_003_000, remaining.single().point.latitudeE6)
        assertTrue(WaypointMarkerWindow.visible(window(), emptyList()).isEmpty())
    }
    @Test fun sideStreetIsNotPlacedOnRoute() {
        assertTrue(WaypointMarkerWindow.visible(window(),
            listOf(stop("Via laterale", 45.002, 9.005))).isEmpty())
    }
}
