package com.motogps.android

import com.motogps.android.ui.extractGeoPoints
import com.motogps.android.viewmodel.PlaceSearchResult
import com.motogps.android.viewmodel.RouteOption
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutePreviewGeometryTest {
    private val destination = PlaceSearchResult(
        "Punto sulla mappa", "", "Mappa", 45.48, 9.18)

    @Test fun noCalculatedRouteDoesNotDrawStraightLine() {
        val points = extractGeoPoints(null, Pair(45.46, 9.15), destination)
        assertTrue("The route preview must not invent a straight line", points.isEmpty())
    }

    @Test fun missingRouteGeometryDoesNotDrawStraightLine() {
        val option = RouteOption("test", "Valhalla", 1200L, 90L,
            emptyList(), JSONObject())
        assertTrue(extractGeoPoints(option, Pair(45.46, 9.15), destination).isEmpty())
    }

    @Test fun validValhallaGeometryIsRetained() {
        val geometry = JSONArray()
            .put(JSONObject().put("latitude_deg", 45.46).put("longitude_deg", 9.15))
            .put(JSONObject().put("latitude_deg", 45.47).put("longitude_deg", 9.16))
            .put(JSONObject().put("latitude_deg", 45.48).put("longitude_deg", 9.18))
        val option = RouteOption("test", "Valhalla", 1800L, 120L,
            emptyList(), JSONObject().put("geometry_gcj02", geometry))
        val points = extractGeoPoints(option, Pair(45.46, 9.15), destination)
        assertEquals(3, points.size)
        assertEquals(45.47, points[1].latitude, 0.000001)
        assertEquals(9.16, points[1].longitude, 0.000001)
    }
}
