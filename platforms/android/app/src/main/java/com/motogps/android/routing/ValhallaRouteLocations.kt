package com.motogps.android.routing

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.hypot

/** Ordered Valhalla stops. 'break' generates legs and arrival/departure guidance. */
internal object ValhallaRouteLocations {
    const val MAX_STOPS = 8

    fun build(
        fromLat: Double, fromLon: Double, toLat: Double, toLon: Double,
        stops: List<Pair<Double, Double>> = emptyList(),
        rerouting: Boolean = false, bearing: Float? = null
    ): JSONArray {
        require(stops.size <= MAX_STOPS) { "Troppe tappe" }
        fun point(lat: Double, lon: Double): JSONObject {
            require(lat.isFinite() && lon.isFinite() &&
                lat in -90.0..90.0 && lon in -180.0..180.0) { "Coordinate non valide" }
            return JSONObject().put("lat", lat).put("lon", lon).put("type", "break")
        }
        val departure = point(fromLat, fromLon)
        if (rerouting && bearing != null && bearing.isFinite() &&
            bearing in 0f..359.999f) {
            departure.put("heading", bearing.toDouble()).put("heading_tolerance", 45)
        }
        val locations = JSONArray().put(departure)
        stops.forEach { locations.put(point(it.first, it.second)) }
        return locations.put(point(toLat, toLon))
    }

    fun suppressUnsupportedAlternates(request: JSONObject): JSONObject {
        if ((request.optJSONArray("locations")?.length() ?: 0) > 2) {
            request.remove("alternates")
        }
        return request
    }

    /** Pass just one intermediate stop for each accepted GPS fix within 60m. */
    fun nextIndex(
        current: Int, stops: List<Pair<Double, Double>>,
        lat: Double, lon: Double, toleranceMeters: Double = 60.0
    ): Int {
        val next = stops.getOrNull(current) ?: return current
        if (!lat.isFinite() || !lon.isFinite()) return current
        val dy = (next.first - lat) * 111_195.0
        val dx = (next.second - lon) * 111_195.0 *
            cos(Math.toRadians((lat + next.first) / 2))
        return if (hypot(dx, dy) <= toleranceMeters) current + 1 else current
    }
}
