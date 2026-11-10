package com.motogps.android

import com.motogps.android.routing.RerouteDepartureDirection
import com.motogps.android.routing.OnlineValhallaRouter
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RerouteDepartureDirectionTest {
    private fun response(vararg points: Pair<Double, Double>): JSONObject {
        val geometry = JSONArray()
        points.forEach { (latitude, longitude) ->
            geometry.put(JSONObject()
                .put("latitude_deg", latitude)
                .put("longitude_deg", longitude))
        }
        return JSONObject().put("route", JSONObject().put("geometry_gcj02", geometry))
    }

    @Test fun movingSouthSouthwestRejectsTheObservedNorthboundDeparture() {
        // Anonymized geometry: reproduces the ~180-degree mismatch at 12:14.
        val route = response(45.0 to 8.0, 45.0004 to 8.00011)
        val analysis = RerouteDepartureDirection.inspect(route, 45.0, 8.0, 199f)
        assertNotNull(analysis)
        assertTrue(analysis!!.isOpposite)
        assertTrue(analysis.differenceDegrees > 150.0)
    }

    @Test fun correctlyOrientedMovingRerouteDoesNotNeedASecondRequest() {
        val route = response(45.0 to 8.0, 44.9996 to 7.99989)
        val analysis = RerouteDepartureDirection.inspect(route, 45.0, 8.0, 199f)
        assertNotNull(analysis)
        assertFalse(analysis!!.isOpposite)
        assertTrue(analysis.differenceDegrees < 35.0)
    }

    @Test fun tinyOppositeConnectorBeforeForwardTravelIsNotMistakenForReverse() {
        val route = response(
            45.0 to 8.0, 45.000009 to 8.0, 44.9996 to 7.99989)
        val analysis = RerouteDepartureDirection.inspect(route, 45.0, 8.0, 199f)
        assertNotNull(analysis)
        assertFalse(analysis!!.isOpposite)
    }

    @Test fun shortAmbiguousRouteCannotBeJudgedByOneTinySegment() {
        assertNull(RerouteDepartureDirection.inspect(
            response(45.0 to 8.0, 45.00008 to 8.0), 45.0, 8.0, 199f))
        assertNull(RerouteDepartureDirection.inspect(
            response(45.0 to 8.0, 45.0005 to 8.0), 45.0, 8.0, null))
    }

    @Test fun preferOnlyBetterNearerDepartureNeverSilentFarawaySnap() {
        val bad = RerouteDepartureDirection.inspect(
            response(45.0 to 8.0, 45.0004 to 8.00011), 45.0, 8.0, 199f)!!
        val good = RerouteDepartureDirection.inspect(
            response(45.0 to 8.0, 44.9996 to 7.99989), 45.0, 8.0, 199f)!!
        val stillWrong = RerouteDepartureDirection.inspect(
            response(45.0 to 8.0, 45.0004 to 8.00004), 45.0, 8.0, 199f)!!
        val tooFar = RerouteDepartureDirection.inspect(
            response(45.001 to 8.0, 45.0006 to 7.99989), 45.0, 8.0, 199f)!!
        assertTrue(RerouteDepartureDirection.preferRetry(bad, good))
        assertFalse(RerouteDepartureDirection.preferRetry(bad, stillWrong))
        assertFalse(RerouteDepartureDirection.preferRetry(bad, tooFar))
        assertFalse(RerouteDepartureDirection.preferRetry(bad, null))
    }

    @Test fun strictSecondRequestPreservesMandatoryWaypointAndDestination() {
        val stops = listOf(45.2 to 9.2, 45.3 to 9.3)
        val initial = OnlineValhallaRouter.request(
            45.0, 8.0, 46.0, 10.0, rerouting = true,
            gpsBearing = 199f, waypoints = stops)
        val retry = OnlineValhallaRouter.request(
            45.0, 8.0, 46.0, 10.0, rerouting = true,
            gpsBearing = 199f, waypoints = stops, strictBearing = true)
        val first = initial.getJSONArray("locations")
        val second = retry.getJSONArray("locations")
        assertEquals(4, second.length())
        for (i in 0 until second.length()) {
            assertEquals(first.getJSONObject(i).getDouble("lat"),
                second.getJSONObject(i).getDouble("lat"), 0.0)
            assertEquals(first.getJSONObject(i).getDouble("lon"),
                second.getJSONObject(i).getDouble("lon"), 0.0)
            assertEquals("break", second.getJSONObject(i).getString("type"))
        }
        assertEquals(199, second.getJSONObject(0).getInt("heading"))
        assertEquals(35, first.getJSONObject(0).getInt("heading_tolerance"))
        assertEquals(35, first.getJSONObject(0).getInt("radius"))
        assertEquals(15, second.getJSONObject(0).getInt("heading_tolerance"))
        assertEquals(65, second.getJSONObject(0).getInt("radius"))
        assertEquals("auto", retry.getString("costing"))
    }
}
