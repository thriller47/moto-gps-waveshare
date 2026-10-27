package com.motogps.android

import com.motogps.android.routing.ValhallaRouteAdapter
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToLong

class ValhallaRouteAdapterTest {
    private fun encode(points: List<Pair<Double, Double>>): String {
        var lastLat = 0L
        var lastLon = 0L
        val output = StringBuilder()
        fun writeDelta(delta: Long) {
            var bits = if (delta < 0) (delta shl 1).inv() else delta shl 1
            while (bits >= 32L) {
                output.append(((32L or (bits and 31L)) + 63L).toInt().toChar())
                bits = bits shr 5
            }
            output.append((bits + 63L).toInt().toChar())
        }
        points.forEach { (lat, lon) ->
            val latE6 = (lat * 1_000_000).roundToLong()
            val lonE6 = (lon * 1_000_000).roundToLong()
            writeDelta(latE6 - lastLat)
            writeDelta(lonE6 - lastLon)
            lastLat = latE6
            lastLon = lonE6
        }
        return output.toString()
    }

    @Test
    fun decodesPolyline6WithNegativeLongitudeAndTightCorners() {
        val input = listOf(
            42.506300 to -1.521800,
            42.506305 to -1.521790,
            42.506400 to -1.521810,
            42.506410 to -1.521820
        )
        val actual = ValhallaRouteAdapter.decodePolyline6(encode(input))
        assertEquals(input.size, actual.size)
        for (i in input.indices) {
            assertEquals(input[i].first, actual[i].first, 0.000001)
            assertEquals(input[i].second, actual[i].second, 0.000001)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTruncatedPolyline() {
        ValhallaRouteAdapter.decodePolyline6("~")
    }

    @Test
    fun convertsMultipleLegsToContinuousBleGeometry() {
        val first = listOf(45.10000 to 9.20000, 45.10010 to 9.20020)
        val second = listOf(first.last(), 45.10020 to 9.20030)
        fun leg(points: List<Pair<Double, Double>>) = JSONObject()
            .put("shape", encode(points))
            .put("summary", JSONObject().put("length", 0.5))
            .put("maneuvers", JSONArray().put(
                JSONObject().put("type", 10)
                    .put("begin_shape_index", 0)
                    .put("length", 0.5)
                    .put("instruction", "Svolta a destra")
                    .put("street_names", JSONArray().put("Via Roma"))
            ))
        val trip = JSONObject()
            .put("summary", JSONObject().put("length", 1.0).put("time", 100))
            .put("legs", JSONArray().put(leg(first)).put(leg(second)))
        val result = ValhallaRouteAdapter.toMotoRoute(
            JSONObject().put("trip", trip), 45.1, 9.2, 45.1002, 9.2003)
        val route = result.getJSONObject("route")
        assertEquals(3, route.getJSONArray("geometry_gcj02").length())
        assertEquals(2, route.getJSONArray("maneuvers").length())
        assertEquals("Via Roma", route.getJSONArray("maneuvers").getJSONObject(0)
            .getString("road_name"))
        assertEquals(1000L, route.getLong("total_distance_m"))
        assertEquals(100L, route.getLong("estimated_duration_s"))
        assertEquals("valhalla-local", route.getString("provider"))
        assertTrue(result.getInt("protocol_version") == 1)
    }
}
