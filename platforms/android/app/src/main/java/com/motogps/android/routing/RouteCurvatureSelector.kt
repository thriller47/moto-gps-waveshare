package com.motogps.android.routing

import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * First-stage bend preference: rank real Valhalla alternative shapes by
 * sustained direction changes, while bounding time and distance detours.
 * This is not a panorama/landscape rating. Never invent route geometry.
 */
internal object RouteCurvatureSelector {
    fun select(response: JSONObject, preferences: RoutePreferences): JSONObject {
        if (!preferences.useCurvyAlternates) return response
        val primary = response.optJSONObject("trip") ?: return response
        val baseTime = primary.optJSONObject("summary")?.optDouble("time", 0.0) ?: 0.0
        val baseKm = primary.optJSONObject("summary")?.optDouble("length", 0.0) ?: 0.0
        if (baseTime <= 0.0 || baseKm <= 0.0) return response
        val primaryBends = bendDensity(primary) ?: return response
        val alternates = response.optJSONArray("alternates") ?: return response

        // Extra travel permitted depends on the rider's curve intensity.
        val maxTimeFactor = 1.0 + 0.08 * preferences.curveLevel
        val maxLengthFactor = 1.0 + 0.13 * preferences.curveLevel
        var best = primary
        var bestScore = primaryBends
        for (index in 0 until min(2, alternates.length())) {
            val trip = alternates.optJSONObject(index)?.optJSONObject("trip") ?: continue
            val summary = trip.optJSONObject("summary") ?: continue
            val time = summary.optDouble("time", 0.0)
            val distance = summary.optDouble("length", 0.0)
            if (!time.isFinite() || !distance.isFinite() || time <= 0.0 ||
                distance <= 0.0 || time > baseTime * maxTimeFactor ||
                distance > baseKm * maxLengthFactor) continue
            val bends = bendDensity(trip) ?: continue
            val detourTime = max(0.0, time / baseTime - 1.0)
            val detourKm = max(0.0, distance / baseKm - 1.0)
            // Penalize gratuitous loops; higher intensity tolerates detours.
            val penalty = (24.0 / preferences.curveLevel) * (detourTime + detourKm)
            val score = bends - penalty
            if (score > bestScore + 1.0) {
                bestScore = score
                best = trip
            }
        }
        // Keep the chosen trip's own legs, maneuvers and summary together.
        return if (best === primary) response else JSONObject().put("trip", best)
    }

    /** Degrees of meaningful bends per km. Discard tiny digitization noise
     * and hairpins that may indicate an artificial loop rather than enjoyment.
     */
    internal fun bendDensity(trip: JSONObject): Double? {
        val legs = trip.optJSONArray("legs") ?: return null
        val distanceKm = trip.optJSONObject("summary")?.optDouble("length", 0.0) ?: 0.0
        if (distanceKm <= 0.0 || !distanceKm.isFinite()) return null
        var turns = 0.0
        for (n in 0 until legs.length()) {
            val shape = legs.optJSONObject(n)?.optString("shape").orEmpty()
            val points = try { ValhallaRouteAdapter.decodePolyline6(shape) }
                catch (_: Exception) { continue }
            if (points.size < 3) continue
            val sampled = ArrayList<Pair<Double, Double>>()
            sampled.add(points.first())
            for (i in 1 until points.size) {
                if (meters(sampled.last(), points[i]) >= 55.0) sampled.add(points[i])
            }
            if (sampled.size < 3) continue
            for (i in 1 until sampled.lastIndex) {
                val a = sampled[i - 1]
                val b = sampled[i]
                val c = sampled[i + 1]
                val cosLat = cos(b.first * PI / 180.0)
                val x1 = (b.second - a.second) * cosLat
                val y1 = b.first - a.first
                val x2 = (c.second - b.second) * cosLat
                val y2 = c.first - b.first
                val mag = sqrt((x1*x1+y1*y1)*(x2*x2+y2*y2))
                if (mag <= 0.0) continue
                val degrees = acos(((x1*x2+y1*y2)/mag).coerceIn(-1.0,1.0)) * 180.0 / PI
                if (degrees in 12.0..145.0) turns += min(degrees, 65.0)
            }
        }
        return turns / distanceKm
    }

    private fun meters(a: Pair<Double,Double>, b: Pair<Double,Double>): Double {
        val dy = (b.first-a.first)*111_195.0
        val dx = (b.second-a.second)*111_195.0*cos((a.first+b.first)*PI/360.0)
        return sqrt(dx*dx+dy*dy)
    }
}
