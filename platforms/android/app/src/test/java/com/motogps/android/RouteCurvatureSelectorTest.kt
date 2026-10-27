package com.motogps.android

import com.motogps.android.routing.RouteCurvatureSelector
import com.motogps.android.routing.RoutePreferences
import com.motogps.android.routing.RouteStyle
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToLong

class RouteCurvatureSelectorTest {
    private fun polyline(points: List<Pair<Double, Double>>): String {
        var lastLat = 0L
        var lastLon = 0L
        val result = StringBuilder()
        fun append(value: Long) {
            var v = if (value < 0L) (value shl 1).inv() else value shl 1
            while (v >= 0x20) {
                result.append(((v and 0x1f) or 0x20).toInt().plus(63).toChar())
                v = v shr 5
            }
            result.append((v.toInt() + 63).toChar())
        }
        for ((lat, lon) in points) {
            val la = (lat * 1_000_000).roundToLong()
            val lo = (lon * 1_000_000).roundToLong()
            append(la - lastLat)
            append(lo - lastLon)
            lastLat = la
            lastLon = lo
        }
        return result.toString()
    }
    private fun trip(shape: String, km: Double, seconds: Double) = JSONObject()
        .put("summary", JSONObject().put("length", km).put("time", seconds))
        .put("legs", JSONArray().put(JSONObject().put("shape", shape)))

    private val straight = polyline(listOf(
        45.0 to 9.0, 45.0 to 9.002, 45.0 to 9.004, 45.0 to 9.006,
        45.0 to 9.008, 45.0 to 9.010
    ))
    private val bends = polyline(listOf(
        45.0 to 9.0, 45.0008 to 9.0008, 45.0 to 9.0016,
        45.0008 to 9.0024, 45.0 to 9.0032, 45.0008 to 9.0040,
        45.0 to 9.0050
    ))

    @Test fun highCurvePreferenceSelectsValidBendyAlternative() {
        val primary = trip(straight, 2.0, 600.0)
        val alternative = trip(bends, 2.2, 660.0)
        val original = JSONObject().put("trip", primary)
            .put("alternates", JSONArray().put(JSONObject().put("trip", alternative)))
        val result = RouteCurvatureSelector.select(
            original, RoutePreferences(style = RouteStyle.TOURING, curveLevel = 3))
        assertEquals(alternative.getJSONObject("summary").getDouble("time"),
            result.getJSONObject("trip").getJSONObject("summary").getDouble("time"), 0.0)
    }

    @Test fun largeDetourIsRejectedEvenIfBendy() {
        val primary = trip(straight, 2.0, 600.0)
        val alternate = trip(bends, 4.0, 1200.0)
        val result = RouteCurvatureSelector.select(
            JSONObject().put("trip", primary)
                .put("alternates", JSONArray().put(JSONObject().put("trip", alternate))),
            RoutePreferences(style = RouteStyle.TOURING, curveLevel = 3)
        )
        assertEquals(600.0, result.getJSONObject("trip")
            .getJSONObject("summary").getDouble("time"), 0.0)
    }

    @Test fun withoutAlternatesKeepOriginalRoute() {
        val response = JSONObject().put("trip", trip(straight, 2.0, 600.0))
        assertSame(response, RouteCurvatureSelector.select(
            response, RoutePreferences(style = RouteStyle.TOURING, curveLevel = 2)))
    }
}
