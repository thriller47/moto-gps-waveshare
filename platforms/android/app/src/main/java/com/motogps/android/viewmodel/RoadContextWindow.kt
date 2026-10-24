package com.motogps.android.viewmodel

import com.motogps.android.jni.GeoPointE6
import kotlin.math.cos
import kotlin.math.hypot

/** Local, contiguous street geometry, centred on the rider instead of way[0]. */
internal object RoadContextWindow {
    fun around(points: List<GeoPointE6>, latitude: Double, longitude: Double, maximumPoints: Int): List<GeoPointE6> {
        if (points.size < 2 || maximumPoints < 2) return emptyList()
        if (points.size <= maximumPoints) return points

        val xScale = 111_320.0 * cos(Math.toRadians(latitude))
        val yScale = 111_320.0
        fun x(point: GeoPointE6) = (point.longitudeE6 / 1e6 - longitude) * xScale
        fun y(point: GeoPointE6) = (point.latitudeE6 / 1e6 - latitude) * yScale
        fun segmentLength(index: Int) = hypot(x(points[index + 1]) - x(points[index]), y(points[index + 1]) - y(points[index]))

        var nearest = 0
        var best = Double.POSITIVE_INFINITY
        for (i in 0 until points.lastIndex) {
            val ax = x(points[i])
            val ay = y(points[i])
            val dx = x(points[i + 1]) - ax
            val dy = y(points[i + 1]) - ay
            val lengthSquared = dx * dx + dy * dy
            val t = if (lengthSquared > 1e-6) {
                (-(ax * dx + ay * dy) / lengthSquared).coerceIn(0.0, 1.0)
            } else 0.0
            val distance = (ax + dx * t) * (ax + dx * t) + (ay + dy * t) * (ay + dy * t)
            if (distance < best) {
                best = distance
                nearest = i
            }
        }

        // Retain road both behind and ahead of the rider, including the next junction.
        var left = nearest
        var right = nearest + 1
        var distance = 0.0
        while (left > 0 && distance < 350.0) {
            distance += segmentLength(left - 1)
            left--
        }
        distance = 0.0
        while (right < points.lastIndex && distance < 350.0) {
            distance += segmentLength(right)
            right++
        }

        val local = points.subList(left, right + 1)
        if (local.size <= maximumPoints) return local

        // Prioritise corners, never just the first vertices of an OSM way.
        val selected = mutableListOf(0, local.lastIndex)
        while (selected.size < maximumPoints) {
            var bestIndex = -1
            var largestDeviation = 1.2
            for (span in 0 until selected.lastIndex) {
                val first = selected[span]
                val last = selected[span + 1]
                val ax = x(local[first])
                val ay = y(local[first])
                val dx = x(local[last]) - ax
                val dy = y(local[last]) - ay
                val lengthSquared = dx * dx + dy * dy
                for (index in first + 1 until last) {
                    val vx = x(local[index]) - ax
                    val vy = y(local[index]) - ay
                    val t = if (lengthSquared > 1e-6) {
                        ((vx * dx + vy * dy) / lengthSquared).coerceIn(0.0, 1.0)
                    } else 0.0
                    val deviation = hypot(vx - dx * t, vy - dy * t)
                    if (deviation > largestDeviation) {
                        largestDeviation = deviation
                        bestIndex = index
                    }
                }
            }
            if (bestIndex < 0) break
            selected.add(bestIndex)
            selected.sort()
        }
        return selected.map { local[it] }
    }
}
