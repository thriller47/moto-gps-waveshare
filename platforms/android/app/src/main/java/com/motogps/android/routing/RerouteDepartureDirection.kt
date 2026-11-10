package com.motogps.android.routing

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Validate the actual first metres of a newly calculated Valhalla route.
 * This does not modify directions, stop order, navigation progress or route
 * geometry; it only decides whether a second, more selective request is useful.
 */
internal object RerouteDepartureDirection {
    private const val LOOKAHEAD_METERS = 35.0
    private const val MIN_TRAVELLED_METERS = 20.0
    private const val MIN_DISPLACEMENT_METERS = 12.0
    private const val OPPOSITE_DEGREES = 125.0

    data class Assessment(
        val departureBearingDegrees: Double,
        val differenceDegrees: Double,
        val snapDistanceMeters: Double,
    ) {
        val isOpposite: Boolean get() = differenceDegrees >= OPPOSITE_DEGREES
    }

    fun inspect(
        routeResponse: JSONObject,
        originLat: Double,
        originLon: Double,
        travelBearing: Float?,
    ): Assessment? {
        if (travelBearing == null || !travelBearing.isFinite() ||
            !originLat.isFinite() || !originLon.isFinite()) return null
        val geometry = routeResponse.optJSONObject("route")
            ?.optJSONArray("geometry_gcj02") ?: return null
        if (geometry.length() < 2) return null
        fun point(i: Int): Pair<Double, Double>? {
            val p = geometry.optJSONObject(i) ?: return null
            val latitude = p.optDouble("latitude_deg", Double.NaN)
            val longitude = p.optDouble("longitude_deg", Double.NaN)
            return if (latitude.isFinite() && longitude.isFinite() &&
                latitude in -90.0..90.0 && longitude in -180.0..180.0)
                latitude to longitude else null
        }
        val first = point(0) ?: return null
        var previous = first
        var endpoint = first
        var travelled = 0.0
        for (i in 1 until geometry.length()) {
            val next = point(i) ?: return null
            val segment = meters(previous, next)
            if (segment > 0.01) {
                val fraction = min(1.0, (LOOKAHEAD_METERS - travelled) / segment)
                endpoint = previous.first + (next.first - previous.first) * fraction to
                    previous.second + (next.second - previous.second) * fraction
                travelled += min(segment, LOOKAHEAD_METERS - travelled)
            }
            previous = next
            if (travelled >= LOOKAHEAD_METERS - 0.01) break
        }
        if (travelled < MIN_TRAVELLED_METERS ||
            meters(first, endpoint) < MIN_DISPLACEMENT_METERS) return null

        val north = (endpoint.first - first.first) * 111_195.0
        val east = (endpoint.second - first.second) * 111_195.0 *
            cos(Math.toRadians((first.first + endpoint.first) / 2.0))
        val bearing = (Math.toDegrees(atan2(east, north)) + 360.0) % 360.0
        val course = (travelBearing.toDouble() % 360.0 + 360.0) % 360.0
        val difference = abs((bearing - course + 540.0) % 360.0 - 180.0)
        return Assessment(bearing, difference,
            meters(first, originLat to originLon))
    }

    /** Keep the original if the retry still runs backwards, or jumps to a
     * substantially farther road. A mandatory waypoint might require a U-turn.
     */
    fun preferRetry(original: Assessment, retry: Assessment?): Boolean =
        retry != null && retry.differenceDegrees < 115.0 &&
            retry.differenceDegrees + 30.0 < original.differenceDegrees &&
            retry.snapDistanceMeters <= max(45.0, original.snapDistanceMeters + 18.0)

    private fun meters(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val north = (b.first - a.first) * 111_195.0
        val east = (b.second - a.second) * 111_195.0 *
            cos(Math.toRadians((a.first + b.first) / 2.0))
        return hypot(east, north)
    }
}
