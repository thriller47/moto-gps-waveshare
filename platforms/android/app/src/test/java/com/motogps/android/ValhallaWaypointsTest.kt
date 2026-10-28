package com.motogps.android

import com.motogps.android.routing.OnlineValhallaRouter
import com.motogps.android.routing.RoutePreferences
import com.motogps.android.routing.RouteStyle
import com.motogps.android.routing.ValhallaRouteLocations
import org.junit.Assert.*
import org.junit.Test

class ValhallaWaypointsTest {
    private val stops = listOf(45.7 to 9.2, 45.9 to 9.4)

    @Test fun stopsAreOrderedBreakLocations() {
        val json = OnlineValhallaRouter.request(
            45.5, 9.0, 46.1, 9.6, waypoints = stops)
        val locations = json.getJSONArray("locations")
        assertEquals(4, locations.length())
        assertEquals(45.7, locations.getJSONObject(1).getDouble("lat"), 0.000001)
        assertEquals(45.9, locations.getJSONObject(2).getDouble("lat"), 0.000001)
        assertEquals(46.1, locations.getJSONObject(3).getDouble("lat"), 0.000001)
        for (i in 0 until locations.length()) {
            assertEquals("break", locations.getJSONObject(i).getString("type"))
        }
    }

    @Test fun multipointTouringDisablesUnsupportedAlternatesOnly() {
        val prefs = RoutePreferences(style = RouteStyle.TOURING, curveLevel = 3)
        val multi = OnlineValhallaRouter.request(
            45.5, 9.0, 46.1, 9.6, preferences = prefs, waypoints = stops)
        assertFalse(multi.has("alternates"))
        assertTrue(multi.getJSONObject("costing_options")
            .getJSONObject("auto").getBoolean("exclude_unpaved"))
        val direct = OnlineValhallaRouter.request(
            45.5, 9.0, 46.1, 9.6, preferences = prefs)
        assertEquals(2, direct.getInt("alternates"))
    }

    @Test fun reroutingKeepsPendingStopsAndGpsHeading() {
        val json = OnlineValhallaRouter.request(
            45.81, 9.33, 46.1, 9.6, rerouting = true,
            gpsBearing = 120f, waypoints = stops.drop(1))
        val locations = json.getJSONArray("locations")
        assertEquals(3, locations.length())
        assertEquals(45.9, locations.getJSONObject(1).getDouble("lat"), 0.000001)
        assertEquals(120.0, locations.getJSONObject(0).getDouble("heading"), 0.0)
    }

    @Test fun marksReachedWaypointsOneByOne() {
        assertEquals(0, ValhallaRouteLocations.nextIndex(0, stops, 45.5, 9.0))
        assertEquals(1, ValhallaRouteLocations.nextIndex(0, stops, 45.7, 9.2))
        assertEquals(2, ValhallaRouteLocations.nextIndex(1, stops, 45.9, 9.4))
        assertEquals(2, ValhallaRouteLocations.nextIndex(2, stops, 45.9, 9.4))
    }

    @Test fun ordinaryRouteStaysTwoPoint() {
        assertEquals(2, OnlineValhallaRouter.request(
            45.5, 9.0, 46.1, 9.6).getJSONArray("locations").length())
    }

    @Test(expected = IllegalArgumentException::class)
    fun tooManyWaypointsRejected() {
        ValhallaRouteLocations.build(45.5, 9.0, 46.1, 9.6,
            List(ValhallaRouteLocations.MAX_STOPS + 1) { 45.7 to 9.2 })
    }
}
