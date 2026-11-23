package com.motogps.android.viewmodel

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadDnaTerrainTest {
    private fun route(vararg points: Pair<Double, Double>): JSONObject {
        val geometry = JSONArray()
        for ((lat, lon) in points) {
            geometry.put(
                JSONObject()
                    .put("latitude_deg", lat)
                    .put("longitude_deg", lon)
            )
        }
        return JSONObject()
            .put("geometry_gcj02", geometry)
            .put("total_distance_m", 400)
    }

    @Test
    fun straightRouteHasNoInventedHairpinsOrElevation() {
        val route = route(
            45.000 to 9.0,
            45.001 to 9.0,
            45.002 to 9.0,
            45.003 to 9.0,
            45.004 to 9.0
        )
        val analysis = RoadDnaTerrainAnalysis.analyze(route)
        assertEquals(0, analysis.curves.total)
        assertEquals(0, analysis.curves.hairpins)
        assertNull(analysis.elevation)
    }

    @Test
    fun availableDemSamplesGenerateRealRangeAndAscent() {
        val route = route(45.0 to 9.0, 45.001 to 9.0, 45.002 to 9.0)
        val values = listOf(100, 110, 115, 130, 145, 140, 135, 120)
        val elevation = JSONArray()
        for ((index, value) in values.withIndex()) {
            elevation.put(
                JSONObject()
                    .put("distance_m", index * 30)
                    .put("elevation_m", value)
            )
        }
        route.put("elevation_profile", elevation)
        val analysis = RoadDnaTerrainAnalysis.analyze(route)
        val result = checkNotNull(analysis.elevation)
        assertEquals(100, result.minMeters)
        assertEquals(145, result.maxMeters)
        assertTrue(result.ascentMeters > 0)
        assertTrue(result.descentMeters > 0)
        assertEquals(values.size, result.points.size)
    }

    @Test
    fun sparseMissingDemSamplesAreNotSilentlyReplacedWithZero() {
        val route = route(45.0 to 9.0, 45.001 to 9.0)
        val elevation = JSONArray()
        for (i in 0 until 10) {
            elevation.put(
                JSONObject()
                    .put("distance_m", i * 30)
                    .put("elevation_m", if (i < 4) 100 + i else JSONObject.NULL)
            )
        }
        route.put("elevation_profile", elevation)
        assertNull(RoadDnaTerrainAnalysis.analyze(route).elevation)
    }

    @Test
    fun curvyGeometryIsCountedButNotLabeledAsSafeSpeed() {
        val route = route(
            45.0 to 9.0,
            45.0 to 9.001,
            45.001 to 9.001,
            45.001 to 9.0,
            45.002 to 9.0
        )
        val analysis = RoadDnaTerrainAnalysis.analyze(route)
        assertTrue(analysis.curves.total > 0)
        assertTrue(analysis.curves.index.contains("indicativa"))
    }
}
