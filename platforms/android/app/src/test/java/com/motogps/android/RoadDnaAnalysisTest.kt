package com.motogps.android

import com.motogps.android.viewmodel.RoadDnaAnalysis
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadDnaAnalysisTest {
    @Test fun readsActualRouteGeometryNotAnInventedStraightLine() {
        val route = JSONObject().put("geometry_gcj02", JSONArray()
            .put(JSONObject().put("latitude_deg", 44.0).put("longitude_deg", 7.0))
            .put(JSONObject().put("latitude_deg", 44.1).put("longitude_deg", 7.1))
            .put(JSONObject().put("latitude_deg", 44.2).put("longitude_deg", 7.2)))
        val points = RoadDnaAnalysis.geometry(route)
        assertEquals(3, points.size)
        assertEquals(44.1, points[1].first, 0.000001)
        assertEquals(7.1, points[1].second, 0.000001)
    }

    @Test fun rejectsMissingRouteGeometry() {
        assertTrue(RoadDnaAnalysis.geometry(JSONObject()).isEmpty())
        assertEquals("Dati insufficienti", RoadDnaAnalysis.curves(emptyList()))
    }

    @Test fun segmentDistanceIsNotEndpointOnly() {
        val distance = RoadDnaAnalysis.distanceMeters(
            45.0 to 7.05, 45.0 to 7.0, 45.0 to 7.1)
        assertTrue(distance < 2.0)
        val far = RoadDnaAnalysis.distanceMeters(
            45.05 to 7.05, 45.0 to 7.0, 45.0 to 7.1)
        assertTrue(far > 5000.0)
    }
}
