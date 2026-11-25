package com.motogps.android

import com.motogps.android.routing.OnlineValhallaRouter
import com.motogps.android.routing.RoutePreferences
import com.motogps.android.routing.RouteStyle
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class RoutePreferencesTest {
    @Test fun defaultRemainsCompatibleWithExistingRoutes() {
        val json = OnlineValhallaRouter.request(45.5, 9.0, 44.5, 10.0)
        assertEquals("auto", json.getString("costing"))
        assertFalse(json.has("costing_options"))
    }

    @Test fun shorterRouteDoesNotPretendToAvoidRoads() {
        val settings = RoutePreferences(RouteStyle.SHORTEST, true, true, true)
        val options = settings.applyTo(JSONObject())
            .getJSONObject("costing_options").getJSONObject("auto")
        assertTrue(options.getBoolean("shortest"))
        assertFalse(options.has("use_highways"))
        assertFalse(options.has("use_tolls"))
        assertFalse(options.has("use_ferry"))
    }

    @Test fun touringAvoidsUnpavedAndPrefersSecondaryAsphalt() {
        val settings = RoutePreferences(
            style = RouteStyle.TOURING, curveLevel = 2,
            avoidTolls = true
        )
        val request = OnlineValhallaRouter.request(
            45.5, 9.0, 44.5, 10.0, preferences = settings)
        assertEquals("motorcycle", request.getString("costing"))
        assertFalse(request.has("alternates"))
        val auto = request.getJSONObject("costing_options").getJSONObject("motorcycle")
        assertEquals(0.8, auto.getDouble("use_curvature"), 0.0001)
        assertEquals(0.15, auto.getDouble("use_highways"), 0.0001)
        assertEquals(0.0, auto.getDouble("use_trails"), 0.0)
        assertTrue(auto.getBoolean("exclude_unpaved"))
        assertEquals(0.0, auto.getDouble("use_tolls"), 0.0)
    }

    @Test fun nativeCurvatureLevelsAndRerouteUseMotorcycle() {
        for ((level, expected) in listOf(0.5, 0.6, 0.8, 1.0).withIndex()) {
            val json = OnlineValhallaRouter.request(45.1, 7.1, 45.2, 7.2,
                rerouting = true, gpsBearing = 90f,
                preferences = RoutePreferences(RouteStyle.TOURING, curveLevel = level))
            assertEquals("motorcycle", json.getString("costing"))
            assertEquals(expected, json.getJSONObject("costing_options")
                .getJSONObject("motorcycle").getDouble("use_curvature"), 0.001)
            assertFalse(json.has("alternates"))
        }
    }

    @Test fun noExtraAlternatesOutsideTouringOrWhenCurvesDisabled() {
        val secondary = RoutePreferences(style = RouteStyle.SECONDARY, curveLevel = 3)
        assertFalse(secondary.applyTo(JSONObject()).has("alternates"))
        val touring = RoutePreferences(style = RouteStyle.TOURING, curveLevel = 0)
        assertFalse(touring.applyTo(JSONObject()).has("alternates"))
    }

    @Test fun secondaryRoadsPenalizeHighwaysAndTracks() {
        val options = RoutePreferences(RouteStyle.SECONDARY)
            .applyTo(JSONObject()).getJSONObject("costing_options").getJSONObject("auto")
        assertEquals(0.0, options.getDouble("use_highways"), 0.0)
        assertEquals(0.0, options.getDouble("use_tracks"), 0.0)
    }

    @Test fun routeAvoidanceIsSentToValhallaAlsoOnReroute() {
        val settings = RoutePreferences(avoidHighways = true, avoidTolls = true, avoidFerries = true)
        val json = OnlineValhallaRouter.request(
            45.5, 9.0, 44.5, 10.0, rerouting = true,
            gpsBearing = 90f, preferences = settings
        )
        val options = json.getJSONObject("costing_options").getJSONObject("auto")
        assertEquals(0.0, options.getDouble("use_highways"), 0.0)
        assertEquals(0.0, options.getDouble("use_tolls"), 0.0)
        assertEquals(0.0, options.getDouble("use_ferry"), 0.0)
        assertEquals(90.0, json.getJSONArray("locations")
            .getJSONObject(0).getDouble("heading"), 0.0)
    }
}
