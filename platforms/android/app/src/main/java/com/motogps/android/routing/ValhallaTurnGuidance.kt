package com.motogps.android.routing

import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.asin
import kotlin.math.sqrt

/**
 * Extract the *next Valhalla maneuver* from the actual route vertices, not
 * local three-point geometry guesses. Offsets are distances ALONG the route.
 * Both local JNI and online Valhalla use this exact same normalized schema.
 */
internal object ValhallaTurnGuidance {
    data class Action(
        val index: Long,
        val shapeMeters: Double,
        val type: String,
        val roadName: String,
        val instruction: String
    )

    data class Plan(val totalShapeMeters: Double, val actions: List<Action>) {
        data class Next(val action: Action, val meters: Long)
        fun next(remainingMeters: Long): Next? {
            if (actions.isEmpty() || remainingMeters < 0L) return null
            val progress = (totalShapeMeters - remainingMeters).coerceIn(0.0, totalShapeMeters)
            // Move to the next maneuver only after the current one has passed
            // by at least 15m. Avoid flicker at the GPS snap-to-road boundary.
            val action = actions.firstOrNull { it.shapeMeters + 15.0 >= progress }
                ?: return null
            return Next(action, (action.shapeMeters - progress)
                .coerceAtLeast(0.0).toLong())
        }
    }

    fun fromRoute(route: JSONObject): Plan {
        val coordinates = route.optJSONArray("geometry_gcj02")
            ?: return Plan(0.0, emptyList())
        val points = ArrayList<Pair<Double, Double>>(coordinates.length())
        for(i in 0 until coordinates.length()) {
            val point = coordinates.optJSONObject(i)
                ?: return Plan(0.0, emptyList())
            val lat = point.optDouble("latitude_deg", Double.NaN)
            val lon = point.optDouble("longitude_deg", Double.NaN)
            if (!lat.isFinite() || !lon.isFinite()) return Plan(0.0, emptyList())
            points.add(lat to lon)
        }
        if(points.size < 2) return Plan(0.0, emptyList())
        val cumulative = DoubleArray(points.size)
        for(i in 1 until points.size) {
            cumulative[i] = cumulative[i - 1] + distanceMeters(
                points[i - 1], points[i])
        }
        val maneuvers = route.optJSONArray("maneuvers")
            ?: return Plan(cumulative.last(), emptyList())
        val actions = ArrayList<Action>()
        for(i in 0 until maneuvers.length()) {
            val m = maneuvers.optJSONObject(i) ?: continue
            val type = m.optString("type", "continue")
            // Start/continue are not a future turn; don't let the phone
            // show a perpetual straight arrow from the departure event.
            val actionable = type in setOf(
                "turn_left", "turn_right", "keep_left", "keep_right",
                "u_turn", "roundabout"
            ) || (type == "arrive" && i == maneuvers.length() - 1)
            if (!actionable) continue
            val index = m.optInt("route_point_index", -1)
            if(index !in points.indices) continue
            actions.add(Action(
                m.optLong("id", (i + 1).toLong()),
                cumulative[index],
                type,
                m.optString("road_name", ""),
                m.optString("instruction", "")
            ))
        }
        return Plan(cumulative.last(), actions.sortedBy { it.shapeMeters })
    }

    private fun distanceMeters(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val lat1 = Math.toRadians(a.first)
        val lat2 = Math.toRadians(b.first)
        val dLat = (lat2 - lat1) / 2.0
        val dLon = Math.toRadians(b.second - a.second) / 2.0
        val h = (kotlin.math.sin(dLat) * kotlin.math.sin(dLat) +
            cos(lat1) * cos(lat2) * kotlin.math.sin(dLon) * kotlin.math.sin(dLon))
            .coerceIn(0.0, 1.0)
        return 2.0 * 6_371_000.0 * asin(sqrt(h))
    }
}
