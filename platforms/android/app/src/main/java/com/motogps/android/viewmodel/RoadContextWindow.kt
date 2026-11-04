package com.motogps.android.viewmodel

import com.motogps.android.jni.GeoPointE6
import kotlin.math.cos
import kotlin.math.hypot

/** Local, contiguous street geometry, centred on the rider instead of way[0]. */
internal object RoadContextWindow {
    /**
     * Preserve the entire closed ring, including the return to the first
     * vertex. The usual rider-centred clipping breaks roundabouts in half.
     * Arc-length resampling distributes the small vertex budget around the
     * whole loop rather than dropping most of one side.
     */
    fun closedRing(points: List<GeoPointE6>, latitude: Double, maximumPoints: Int): List<GeoPointE6> {
        if (points.size < 4 || maximumPoints < 4) return emptyList()
        if (points.size <= maximumPoints) return points
        val scale = 111_320.0 * cos(Math.toRadians(latitude))
        fun length(a: GeoPointE6, b: GeoPointE6) = hypot(
            (b.latitudeE6 - a.latitudeE6) / 1e6 * 111_320.0,
            (b.longitudeE6 - a.longitudeE6) / 1e6 * scale)
        val cumulative = DoubleArray(points.size)
        for (i in 1 until points.size) {
            cumulative[i] = cumulative[i - 1] + length(points[i - 1], points[i])
        }
        val total = cumulative.last()
        if (total <= 0.1) return emptyList()
        val result = ArrayList<GeoPointE6>(maximumPoints)
        for (j in 0 until maximumPoints - 1) {
            val target = total * j / (maximumPoints - 1)
            var i = 1
            while (i < cumulative.lastIndex && cumulative[i] < target) i++
            val segment = (cumulative[i] - cumulative[i - 1]).coerceAtLeast(1e-6)
            val fraction = ((target - cumulative[i - 1]) / segment).coerceIn(0.0, 1.0)
            val a = points[i - 1]
            val b = points[i]
            result.add(GeoPointE6(
                (a.latitudeE6 + (b.latitudeE6 - a.latitudeE6) * fraction).toInt(),
                (a.longitudeE6 + (b.longitudeE6 - a.longitudeE6) * fraction).toInt()
            ))
        }
        result.add(result.first())
        return result
    }

    fun around(points: List<GeoPointE6>, latitude: Double, longitude: Double, maximumPoints: Int): List<GeoPointE6> {
        if (points.size < 2 || maximumPoints < 2) return emptyList()

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
        while (left > 0 && distance < 180.0) {
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
