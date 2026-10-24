package com.motogps.android.viewmodel

import com.motogps.android.jni.GeoPointE6
import com.motogps.android.jni.MotoBLERouteGeometry
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Build a small, moving route window for the circular ESP32 display.
 * The full route must stay on Android for distance/maneuver/off-route logic.
 */
internal object RouteGeometryWindow {
    private const val MAX_POINTS = 24
    private const val LOOKAHEAD_METERS = 550.0
    private const val MIN_TURN_DETAIL_METERS = 1.2

    fun around(route: MotoBLERouteGeometry, latitude: Double, longitude: Double): MotoBLERouteGeometry {
        val full = route.points
        if (full.size < 2 || !latitude.isFinite() || !longitude.isFinite()) return route

        // Local tangent-plane metres, calculated relative to the CURRENT GPS fix.
        val xScale = 111_320.0 * cos(Math.toRadians(latitude))
        val yScale = 111_320.0
        fun x(point: GeoPointE6) = (point.longitudeE6 / 1e6 - longitude) * xScale
        fun y(point: GeoPointE6) = (point.latitudeE6 / 1e6 - latitude) * yScale

        var nearestSegment = 0
        var nearestFraction = 0.0
        var bestSquaredDistance = Double.POSITIVE_INFINITY
        for (i in 0 until full.lastIndex) {
            val ax = x(full[i])
            val ay = y(full[i])
            val dx = x(full[i + 1]) - ax
            val dy = y(full[i + 1]) - ay
            val lengthSquared = dx * dx + dy * dy
            val t = if (lengthSquared > 1e-6) {
                (-(ax * dx + ay * dy) / lengthSquared).coerceIn(0.0, 1.0)
            } else 0.0
            val px = ax + t * dx
            val py = ay + t * dy
            val distanceSquared = px * px + py * py
            if (distanceSquared < bestSquaredDistance) {
                bestSquaredDistance = distanceSquared
                nearestSegment = i
                nearestFraction = t
            }
        }

        val start = interpolate(full[nearestSegment], full[nearestSegment + 1], nearestFraction)
        val ahead = ArrayList<GeoPointE6>()
        ahead.add(start)
        var travelled = 0.0
        var previous = start
        for (i in nearestSegment + 1 until full.size) {
            val next = full[i]
            val distance = hypot(x(next) - x(previous), y(next) - y(previous))
            if (distance < 0.03) continue
            if (travelled + distance > LOOKAHEAD_METERS) {
                ahead.add(interpolate(previous, next, (LOOKAHEAD_METERS - travelled) / distance))
                break
            }
            ahead.add(next)
            travelled += distance
            previous = next
        }

        // Near the destination, retain a real road segment instead of an empty line.
        if (ahead.size < 2) {
            ahead.add(full.last())
            if (ahead[0] == ahead[1]) {
                ahead.clear()
                ahead.add(full[full.lastIndex - 1])
                ahead.add(full.last())
            }
        }

        // Greedy maximum-deviation simplification: retain sharp turns, not just
        // the first 24 vertices. Long straight sections need only their ends.
        val selected = mutableListOf(0, ahead.lastIndex)
        while (selected.size < MAX_POINTS) {
            var bestPoint = -1
            var bestDeviation = MIN_TURN_DETAIL_METERS
            for (span in 0 until selected.lastIndex) {
                val firstIndex = selected[span]
                val lastIndex = selected[span + 1]
                val ax = x(ahead[firstIndex])
                val ay = y(ahead[firstIndex])
                val bx = x(ahead[lastIndex])
                val by = y(ahead[lastIndex])
                val dx = bx - ax
                val dy = by - ay
                val lengthSquared = dx * dx + dy * dy
                for (i in firstIndex + 1 until lastIndex) {
                    val px = x(ahead[i]) - ax
                    val py = y(ahead[i]) - ay
                    val t = if (lengthSquared > 1e-6) {
                        ((px * dx + py * dy) / lengthSquared).coerceIn(0.0, 1.0)
                    } else 0.0
                    val deviation = hypot(px - t * dx, py - t * dy)
                    if (deviation > bestDeviation) {
                        bestDeviation = deviation
                        bestPoint = i
                    }
                }
            }
            if (bestPoint < 0) break
            selected.add(bestPoint)
            selected.sort()
        }

        val points = selected.map { ahead[it] }
        return route.copy(
            chunkIndex = 0,
            chunkCount = 1,
            firstPointIndex = 0,
            totalPointCount = points.size,
            viewOrigin = GeoPointE6((latitude * 1e6).roundToInt(), (longitude * 1e6).roundToInt()),
            points = points
        )
    }

    private fun interpolate(a: GeoPointE6, b: GeoPointE6, fraction: Double): GeoPointE6 {
        val t = fraction.coerceIn(0.0, 1.0)
        return GeoPointE6(
            (a.latitudeE6 + (b.latitudeE6 - a.latitudeE6) * t).roundToInt(),
            (a.longitudeE6 + (b.longitudeE6 - a.longitudeE6) * t).roundToInt()
        )
    }
}
