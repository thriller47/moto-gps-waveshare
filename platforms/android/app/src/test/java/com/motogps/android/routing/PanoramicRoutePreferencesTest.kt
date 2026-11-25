package com.motogps.android.routing

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanoramicRoutePreferencesTest {
    @Test fun panoramicUsesNativeMotorcycleCurvature() {
        val prefs = RoutePreferences(style = RouteStyle.PANORAMIC)
        assertTrue(prefs.useCurvyAlternates)
        assertEquals(0, prefs.requestAlternates())
        val request = prefs.applyTo(JSONObject().put("costing", "auto"))
        val auto = request.getJSONObject("costing_options").getJSONObject("motorcycle")
        assertEquals("motorcycle", request.getString("costing"))
        assertEquals(0.8, auto.getDouble("use_curvature"), 0.001)
        assertEquals(0.05, auto.getDouble("use_highways"), 0.001)
        assertEquals(0.0, auto.getDouble("use_tolls"), 0.001)
        assertTrue(auto.getBoolean("exclude_unpaved"))
        assertFalse(request.has("alternates"))
    }

    @Test fun panoramicWithStopsKeepsRoadPreferencesButDisablesUnsupportedAlternates() {
        val prefs = RoutePreferences(style = RouteStyle.PANORAMIC)
        val request = prefs.applyTo(JSONObject().put(
            "locations", ValhallaRouteLocations.build(
                45.1, 7.1, 45.4, 7.4,
                listOf(45.2 to 7.2, 45.3 to 7.3))))
        ValhallaRouteLocations.suppressUnsupportedAlternates(request)
        assertFalse(request.has("alternates"))
        assertTrue(request.getJSONObject("costing_options")
            .getJSONObject("motorcycle").getBoolean("exclude_unpaved"))
        assertEquals(4, request.getJSONArray("locations").length())
    }

    @Test fun touringAndFastestBehaviorsRemainUnchanged() {
        assertFalse(RoutePreferences().useCurvyAlternates)
        assertFalse(RoutePreferences(style = RouteStyle.TOURING).useCurvyAlternates)
        assertTrue(RoutePreferences(style = RouteStyle.TOURING, curveLevel = 2)
            .useCurvyAlternates)
    }

    @Test fun panoramicCurveSelectionDoesNotDivideByZeroWithoutAlternates() {
        val response = JSONObject()
        val selected = RouteCurvatureSelector.select(
            response, RoutePreferences(style = RouteStyle.PANORAMIC))
        assertTrue(selected === response)
    }
}
