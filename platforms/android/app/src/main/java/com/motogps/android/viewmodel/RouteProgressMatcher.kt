package com.motogps.android.viewmodel

import com.motogps.android.jni.GeoPointE6
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Direction-aware position on one active Valhalla route. Reject backwards
 * nearest-segment jumps at junctions and hold progress while off-route.
 */
internal class RouteProgressMatcher {
    private var route = emptyList<GeoPointE6>()
    private var cumulative = DoubleArray(0)
    private var progress: Double? = null
    private var lastLat = Double.NaN
    private var lastLon = Double.NaN

    fun reset(points: List<GeoPointE6> = emptyList()) {
        route = points
        cumulative = DoubleArray(points.size)
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            cumulative[i] = cumulative[i - 1] + hypot(
                (b.latitudeE6 - a.latitudeE6) / 1e6 * 111_195.0,
                (b.longitudeE6 - a.longitudeE6) / 1e6 *
                    111_195.0 * cos(Math.toRadians(
                    (a.latitudeE6 + b.latitudeE6) / 2e6)))
        }
        progress = null
        lastLat = Double.NaN
        lastLon = Double.NaN
    }

    /** Display-only hint; routing and reroute decisions remain unchanged. */
    fun matchedProgressMeters(): Double? = progress

    /** A reverse candidate consistent with the already matched route progress.
     * The nearest point of the *entire* shape is not enough: on hairpins,
     * parallel roads and self-intersections it can be a different road.
     */
    data class ReverseCandidate(val distanceMeters: Double,
                                val bearingDegrees: Float,
                                val progressDeltaMeters: Double)

    fun reverseCandidate(lat: Double, lon: Double, heading: Float?,
                         speed: Float): ReverseCandidate? {
        val anchor = progress ?: return null
        if (route.size < 2 || !lat.isFinite() || !lon.isFinite() ||
            heading == null || !heading.isFinite() || speed < 4f) return null
        val mx = 111_195.0 * cos(Math.toRadians(lat))
        var reverse: ReverseCandidate? = null
        var reverseScore = Double.POSITIVE_INFINITY
        var nearestForward = Double.POSITIVE_INFINITY

        for (i in 0 until route.lastIndex) {
            val a = route[i]
            val b = route[i + 1]
            val ax = (a.longitudeE6 / 1e6 - lon) * mx
            val ay = (a.latitudeE6 / 1e6 - lat) * 111_195.0
            val dx = (b.longitudeE6 - a.longitudeE6) / 1e6 * mx
            val dy = (b.latitudeE6 - a.latitudeE6) / 1e6 * 111_195.0
            val length2 = dx * dx + dy * dy
            if (length2 < 144.0) continue // Sharp 5m bend is not a reversal.
            val t = (-(ax * dx + ay * dy) / length2).coerceIn(0.0, 1.0)
            val cross = hypot(ax + t * dx, ay + t * dy)
            if (cross > 25.0) continue
            val at = cumulative[i] + (cumulative[i + 1] - cumulative[i]) * t
            val delta = at - anchor
            val direction = Math.toDegrees(atan2(dx, dy)).toFloat()
            val error = abs(((heading - direction + 540f) % 360f) - 180f)
            if (error <= 60f) {
                // Even a later leg of a loop can be the *actual* road.
                // A closer aligned segment must veto a speculative reversal
                // on an older nearby segment, regardless of route progress.
                nearestForward = min(nearestForward, cross)
            } else if (delta >= -110.0 && delta <= 85.0 &&
                RouteDirectionPolicy.isOpposite(
                    heading, direction, speed, cross, kotlin.math.sqrt(length2))) {
                val score = cross + abs(delta) * 0.10
                if (score < reverseScore) {
                    reverseScore = score
                    reverse = ReverseCandidate(cross, direction, delta)
                }
            }
        }
        val candidate = reverse ?: return null
        // If a forward-directed edge is similarly close, the junction match
        // is ambiguous. Prefer continuing to an unnecessary reverse reroute.
        if (nearestForward <= candidate.distanceMeters + 8.0) return null
        return candidate
    }

    fun remaining(lat: Double, lon: Double, heading: Float?, speed: Float): Long? {
        if (route.size < 2 || !lat.isFinite() || !lon.isFinite()) return null
        val mx = 111_195.0 * cos(Math.toRadians(lat))
        val prev = progress
        val travelled = if (lastLat.isFinite() && lastLon.isFinite())
            hypot((lat - lastLat) * 111_195.0,
                (lon - lastLon) * mx)
        else 0.0

        var bestScore = Double.POSITIVE_INFINITY
        var bestPos = 0.0
        var minCross = Double.POSITIVE_INFINITY
        for (i in 0 until route.lastIndex) {
            val a = route[i]
            val b = route[i + 1]
            val ax = (a.longitudeE6 / 1e6 - lon) * mx
            val ay = (a.latitudeE6 / 1e6 - lat) * 111_195.0
            val dx = (b.longitudeE6 - a.longitudeE6) / 1e6 * mx
            val dy = (b.latitudeE6 - a.latitudeE6) / 1e6 * 111_195.0
            val len2 = dx * dx + dy * dy
            if (len2 < 0.01) continue
            val t = (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val cross = hypot(ax + t * dx, ay + t * dy)
            minCross = min(minCross, cross)
            val candidate = cumulative[i] +
                (cumulative[i + 1] - cumulative[i]) * t
            var score = cross
            if (prev != null) {
                if (candidate < prev - 10.0)
                    score += (prev - 10.0 - candidate) * 1.5
                val high = prev + max(22.0, travelled * 1.8 + 10.0)
                if (candidate > high) score += (candidate - high) * 0.8
            }
            if (heading != null && heading.isFinite() && speed >= 3f &&
                len2 >= 144.0) {
                val direction = Math.toDegrees(atan2(dx, dy)).toFloat()
                val error = abs(((heading - direction + 540f) % 360f) - 180f)
                if (error > 90f) score += min(35.0, (error - 90.0) * 0.55)
            }
            if (score < bestScore) {
                bestScore = score
                bestPos = candidate
            }
        }
        if (!bestScore.isFinite()) return null

        // Outside the route corridor the rider is awaiting a reroute:
        // do not count travel away from the route as a distance increase.
        val next = when {
            prev != null && minCross > 45.0 -> prev
            prev == null -> bestPos
            else -> max(prev, min(bestPos,
                prev + max(22.0, travelled * 1.8 + 10.0)))
        }
        progress = next
        lastLat = lat
        lastLon = lon
        return (cumulative.last() - next).coerceAtLeast(0.0).roundToLong()
    }
}
