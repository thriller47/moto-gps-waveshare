package com.motogps.android.map

import com.motogps.android.jni.GeoPointE6
import kotlin.math.cos
import kotlin.math.hypot

/** Route-focused grey road selection based on real OSM-node junctions. */
internal object RoadContextSelection {
    private data class Point(val x: Double, val y: Double)
    private data class Item(val road: MapRoadSegment, val gps: Double, val route: Double,
                            val connected: Boolean = false)

    fun rank(roads: List<MapRoadSegment>, route: List<GeoPointE6>,
             latitude: Double, longitude: Double,
             previousWayIds: Set<Long> = emptySet()): List<MapRoadSegment> {
        if (roads.isEmpty()) return emptyList()
        val scale = 111_320.0 * cos(Math.toRadians(latitude))
        fun xy(p: GeoPointE6) = Point(
            (p.longitudeE6 / 1e6 - longitude) * scale,
            (p.latitudeE6 / 1e6 - latitude) * 111_320.0)
        fun distance(p: Point, a: Point, b: Point): Double {
            val dx = b.x - a.x
            val dy = b.y - a.y
            val len = dx * dx + dy * dy
            val t = if (len > 1e-6)
                (((p.x - a.x) * dx + (p.y - a.y) * dy) / len).coerceIn(0.0, 1.0)
                else 0.0
            return hypot(a.x + t * dx - p.x, a.y + t * dy - p.y)
        }
        val routeSegments = route.zipWithNext().map { (a, b) -> xy(a) to xy(b) }
        val items = roads.map { road ->
            val pts = road.points.map(::xy)
            var gps = Double.POSITIVE_INFINITY
            var toRoute = Double.POSITIVE_INFINITY
            for (i in 0 until pts.lastIndex) {
                val a = pts[i]
                val b = pts[i + 1]
                gps = minOf(gps, distance(Point(0.0, 0.0), a, b))
                if (routeSegments.isNotEmpty()) {
                    val middle = Point((a.x + b.x) / 2.0, (a.y + b.y) / 2.0)
                    for ((start, end) in routeSegments) {
                        toRoute = minOf(toRoute, distance(a, start, end),
                            distance(b, start, end), distance(middle, start, end))
                    }
                }
            }
            Item(road, gps, toRoute)
        }
        if (routeSegments.isEmpty()) return items.sortedBy { it.gps }.map { it.road }
        // Topology means matching node IDs; a 2D crossing alone is insufficient.
        val junctionNodes = HashSet<Long>()
        for (item in items) {
            if (item.gps <= 260.0 && item.route <= 12.0 &&
                item.road.nodeIds.size == item.road.points.size) {
                junctionNodes.addAll(item.road.nodeIds.filter { it > 0L })
            }
        }
        val enriched = items.map { it.copy(
            connected = it.road.nodeIds.any { id -> id in junctionNodes }
        ) }
        val relevant = enriched.filter {
            it.gps <= 280.0 && (it.route <= 100.0 || it.connected)
        }.ifEmpty { enriched.sortedBy { it.gps }.take(16) }
        return relevant.sortedWith(compareBy<Item> {
            it.gps * 0.35 + it.route * 1.4 -
                (if (it.route <= 12.0) 85.0 else 0.0) -
                (if (it.connected) 100.0 else 0.0) -
                (if (it.road.osmWayId in previousWayIds) 18.0 else 0.0)
        }.thenBy { it.gps }.thenBy { it.road.osmWayId ?: Long.MAX_VALUE })
            .map { it.road }
    }
}
