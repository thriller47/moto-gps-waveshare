package com.motogps.android.map

import com.motogps.android.jni.GeoPointE6
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Suppress only *proven* non-connected 2D crossings from MotoMap v3.
 *
 * Valhalla route geometry alone has no OSM node IDs. We first match a
 * road which overlaps the route for a meaningful length and direction,
 * then use that road's genuine OSM node references to identify junctions.
 * Without a reliable match or complete v3 node IDs, never suppress roads.
 *
 * This changes only grey context geometry on Android; it does not change
 * the route, guidance, speed limits, BLE payload or the ESP32 renderer.
 */
internal object RoadContextTopology {
    private const val ON_ROUTE_DISTANCE_METERS = 8.0
    private const val MIN_OVERLAP_METERS = 12.0
    private const val MIN_ALIGNMENT_COS = 0.82
    private const val EPS = 1e-7

    private data class Point(val x: Double, val y: Double)
    private data class Match(val distance: Double, val alignment: Double)

    fun excludeDisconnectedCrossings(
        roads: List<MapRoadSegment>,
        route: List<GeoPointE6>,
        latitude: Double,
        longitude: Double
    ): List<MapRoadSegment> {
        if (route.size < 2 || roads.isEmpty() ||
            !latitude.isFinite() || !longitude.isFinite()) return roads

        val xScale = 111_320.0 * cos(Math.toRadians(latitude))
        val yScale = 111_320.0
        fun projected(point: GeoPointE6) = Point(
            (point.longitudeE6 / 1e6 - longitude) * xScale,
            (point.latitudeE6 / 1e6 - latitude) * yScale
        )
        val path = route.map(::projected)
        val segments = roads.map { road -> road.points.map(::projected) }

        fun nearestRoute(point: Point, roadDirection: Point): Match {
            var best = Double.POSITIVE_INFINITY
            var alignment = 0.0
            val roadLength = hypot(roadDirection.x, roadDirection.y)
            for (i in 0 until path.lastIndex) {
                val a = path[i]
                val b = path[i + 1]
                val dx = b.x - a.x
                val dy = b.y - a.y
                val lengthSquared = dx * dx + dy * dy
                if (lengthSquared < 1e-6) continue
                val t = (((point.x - a.x) * dx + (point.y - a.y) * dy) /
                    lengthSquared).coerceIn(0.0, 1.0)
                val distance = hypot(point.x - a.x - t * dx, point.y - a.y - t * dy)
                if (distance < best) {
                    best = distance
                    alignment = if (roadLength > 0.0) {
                        abs((roadDirection.x * dx + roadDirection.y * dy) /
                            (roadLength * kotlin.math.sqrt(lengthSquared)))
                    } else 0.0
                }
            }
            return Match(best, alignment)
        }

        // OSM can split a way in the middle of a junction. The shared OSM
        // node IDs (not equal-looking screen coordinates) define connectivity.
        val routeNodes = HashSet<Long>()
        val onRouteRoads = HashSet<Int>()
        for (index in roads.indices) {
            val road = roads[index]
            if (road.nodeIds.size != road.points.size || road.nodeIds.any { it <= 0L }) continue
            val pts = segments[index]
            var overlap = 0.0
            for (i in 0 until pts.lastIndex) {
                val a = pts[i]
                val b = pts[i + 1]
                val direction = Point(b.x - a.x, b.y - a.y)
                val length = hypot(direction.x, direction.y)
                if (length < 1.0) continue
                val midpoint = Point((a.x + b.x) * 0.5, (a.y + b.y) * 0.5)
                val match = nearestRoute(midpoint, direction)
                if (match.distance <= ON_ROUTE_DISTANCE_METERS &&
                    match.alignment >= MIN_ALIGNMENT_COS &&
                    nearestRoute(a, direction).distance <= ON_ROUTE_DISTANCE_METERS &&
                    nearestRoute(b, direction).distance <= ON_ROUTE_DISTANCE_METERS) {
                    overlap += length
                    if (overlap >= MIN_OVERLAP_METERS) break
                }
            }
            if (overlap >= MIN_OVERLAP_METERS) {
                onRouteRoads.add(index)
                routeNodes.addAll(road.nodeIds)
            }
        }

        // Without reliable OSM -> route matching, prefer the old complete map
        // instead of accidentally removing real exits and roundabout branches.
        if (routeNodes.isEmpty()) return roads

        fun properCrossing(a: Point, b: Point, c: Point, d: Point): Boolean {
            val ab = Point(b.x - a.x, b.y - a.y)
            val cd = Point(d.x - c.x, d.y - c.y)
            val ac = Point(c.x - a.x, c.y - a.y)
            val denominator = ab.x * cd.y - ab.y * cd.x
            if (abs(denominator) < EPS) return false
            val t = (ac.x * cd.y - ac.y * cd.x) / denominator
            val u = (ac.x * ab.y - ac.y * ab.x) / denominator
            return t > EPS && t < 1.0 - EPS && u > EPS && u < 1.0 - EPS
        }

        fun crossesRoute(points: List<Point>): Boolean {
            for (i in 0 until points.lastIndex) {
                for (j in 0 until path.lastIndex) {
                    if (properCrossing(points[i], points[i + 1], path[j], path[j + 1])) {
                        return true
                    }
                }
            }
            return false
        }

        return roads.filterIndexed { index, road ->
            onRouteRoads.contains(index) ||
                road.nodeIds.size != road.points.size ||
                road.nodeIds.any { routeNodes.contains(it) } ||
                !crossesRoute(segments[index])
        }
    }
}
