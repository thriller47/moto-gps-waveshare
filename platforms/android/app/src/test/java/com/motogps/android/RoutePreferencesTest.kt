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
