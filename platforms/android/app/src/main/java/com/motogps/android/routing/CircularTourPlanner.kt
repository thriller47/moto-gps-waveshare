package com.motogps.android.routing

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Creates three ordered via points surrounding the departure for a circular Valhalla route.
 * These are planning hints, NOT fake road geometry: Valhalla must find every road leg.
 * The chosen distance is indicative because road-network detours are unknown.
 */
internal object CircularTourPlanner {
    const val DEFAULT_DISTANCE_KM = 100
    const val MIN_DISTANCE_KM = 30
    const val MAX_DISTANCE_KM = 300

    /** The circle's centre lies [radius] in [orientation] from the current position. */
    fun generate(
        latitude: Double,
        longitude: Double,
        distanceKm: Int,
        orientationDegrees: Int = 0
    ): List<Pair<Double, Double>> {
        require(latitude.isFinite() && longitude.isFinite() &&
            latitude in -85.0..85.0 && longitude in -180.0..180.0)
        require(distanceKm in MIN_DISTANCE_KM..MAX_DISTANCE_KM)
        require(orientationDegrees in 0..359)

        // Four 90-degree chords approximate the requested 2-D loop perimeter.
        val radiusMeters = distanceKm * 1000.0 / (4.0 * kotlin.math.sqrt(2.0))
        val center = destination(latitude, longitude, orientationDegrees.toDouble(), radiusMeters)
        return listOf(90.0, 0.0, 270.0).map { angle ->
            destination(center.first, center.second,
                (orientationDegrees + angle) % 360.0, radiusMeters)
        }
    }

    /**
     * Mandatory stops always remain in user order. With no mandatory locations
     * use the legacy three-stop circle. With just one add one optional detour
     * point on the side of the selected orientation to avoid an out-and-back.
     * Two or more mandatory locations are never reordered or diluted with
     * synthetic points: only Valhalla decides if a road circuit exists.
     */
    fun planStops(
        latitude: Double, longitude: Double, distanceKm: Int,
        orientationDegrees: Int,
        mandatory: List<Pair<Double, Double>>
    ): List<Pair<Double, Double>> {
        require(mandatory.size <= ValhallaRouteLocations.MAX_STOPS)
        // Validate parameters and geography even when no automatic points are used.
        val generated = generate(latitude, longitude, distanceKm, orientationDegrees)
        if (mandatory.isEmpty()) return generated
        require(mandatory.all { it.first.isFinite() && it.second.isFinite() &&
            it.first in -90.0..90.0 && it.second in -180.0..180.0 })
        if (mandatory.size >= 2) return mandatory

        // Put a single optional stop beyond the mandatory location, offset
        // sideways in the requested tour direction. Never replace the user's POI.
        val mandatoryPoint = mandatory.single()
        val side = (orientationDegrees + 90) % 360
        val detour = destination(mandatoryPoint.first, mandatoryPoint.second,
            side.toDouble(), distanceKm * 125.0)
        return listOf(mandatoryPoint, detour)
    }

    /** Minimum possible distance to visit all compulsory points and return.
     * Used only as a lower bound, not as an estimate of road-network length.
     */
    fun straightLineMinimumKm(
        latitude: Double, longitude: Double,
        mandatory: List<Pair<Double, Double>>
    ): Double {
        if (mandatory.isEmpty()) return 0.0
        val ordered = listOf(latitude to longitude) + mandatory +
            listOf(latitude to longitude)
        return ordered.zipWithNext().sumOf { (a, b) ->
            val dLat = (b.first - a.first) * PI / 180.0
            val dLon = (b.second - a.second) * PI / 180.0
            val lat1 = a.first * PI / 180.0
            val lat2 = b.first * PI / 180.0
            val h = sin(dLat / 2) * sin(dLat / 2) +
                cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
            2.0 * 6371.0 * asin(kotlin.math.sqrt(h.coerceIn(0.0, 1.0)))
        }
    }

    private fun destination(lat: Double, lon: Double, bearingDeg: Double, meters: Double)
        : Pair<Double, Double> {
        val radiusEarth = 6_371_000.0
        val distanceRad = meters / radiusEarth
        val bearing = bearingDeg * PI / 180.0
        val lat1 = lat * PI / 180.0
        val lon1 = lon * PI / 180.0
        val lat2 = asin(sin(lat1) * cos(distanceRad) +
            cos(lat1) * sin(distanceRad) * cos(bearing))
        val lon2 = lon1 + atan2(
            sin(bearing) * sin(distanceRad) * cos(lat1),
            cos(distanceRad) - sin(lat1) * sin(lat2))
        val normalizedLon = ((lon2 * 180.0 / PI + 540.0) % 360.0) - 180.0
        return lat2 * 180.0 / PI to normalizedLon
    }
}
