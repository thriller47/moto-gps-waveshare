package com.motogps.android.routing

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineValhallaElevationTest {
    private fun route(): JSONObject {
        val points = JSONArray()
        for (i in 0..8) {
            points.put(JSONObject()
                .put("latitude_deg", 45.0 + i * 0.001)
                .put("longitude_deg", 9.0 + (i % 2) * 0.0002))
        }
        return JSONObject().put("geometry_gcj02", points)
    }

    @Test
    fun heightRequestFollowsRealRouteWithoutRecalculating() {
        val r = route()
        val previous = r.toString()
        val payload = checkNotNull(OnlineValhallaElevation.heightRequest(r))
        assertTrue(payload.optBoolean("range"))
        assertEquals("polyline6", payload.optString("shape_format"))
        assertTrue(payload.getString("encoded_polyline").length > 10)
        // Reuse the adapter's production polyline6 decoder for coordinate order.
        val decoded = ValhallaRouteAdapter.decodePolyline6(
            payload.getString("encoded_polyline"))
        assertEquals(45.0, decoded.first().first, 0.000002)
        assertEquals(9.0, decoded.first().second, 0.000002)
        assertEquals(45.008, decoded.last().first, 0.000002)
        assertEquals(previous, r.toString())
        assertNull(OnlineValhallaElevation.heightRequest(JSONObject()))
    }

    @Test
    fun validRangeHeightIsConvertedToDemSummary() {
        val series = JSONArray()
        for (i in 0..9) {
            val h = when {
                i < 5 -> 300 + i * 12
                else -> 300 + (9 - i) * 12
            }
            series.put(JSONArray().put(i * 100.0).put(h))
        }
        val e = OnlineValhallaElevation.parseHeightResponse(
            JSONObject().put("range_height", series))
        assertNotNull(e)
        assertEquals(300, e!!.minMeters)
        assertEquals(348, e.maxMeters)
        assertTrue(e.ascentMeters > 0)
        assertTrue(e.descentMeters > 0)
    }

    @Test
    fun emptyAndMissingDemResponsesRemainUnavailable() {
        assertNull(OnlineValhallaElevation.parseHeightResponse(JSONObject()))
        val series = JSONArray()
        for (i in 0..9) {
            series.put(JSONArray().put(i * 30.0).put(
                if (i < 3) 300 else JSONObject.NULL))
        }
        assertNull(OnlineValhallaElevation.parseHeightResponse(
            JSONObject().put("range_height", series)))
    }

    @Test
    fun rejectUnorderedRangesAndUnknownResponseFields() {
        val series = JSONArray()
            .put(JSONArray().put(0).put(100))
            .put(JSONArray().put(100).put(110))
            .put(JSONArray().put(90).put(120))
        assertNull(OnlineValhallaElevation.parseHeightResponse(
            JSONObject().put("range_height", series)))
        assertNull(OnlineValhallaElevation.parseHeightResponse(
            JSONObject().put("height", JSONArray().put(100).put(110))))
    }
}
