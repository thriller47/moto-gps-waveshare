package com.motogps.android.viewmodel

import com.motogps.android.jni.GeoPointE6
import com.motogps.android.map.MapRoadSegment
import com.motogps.android.map.RoadContextJunctions
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Explicit BLE scene allocation. Focus + exits have priority, but at least
 * six independent GPS-local roads are reserved BEFORE upcoming/extra roads.
 * A single ring and up to seven distinct outgoing branches take precedence
 * over additional, less relevant local streets (20 roads / 160 points total).
 * All geometry is chosen from MotoMap, never restricted to the calculated
 * route: the rider must still see streets during a deviation.
 */
internal object RoadContextSceneSelector {
    const val MAX_ROADS = 20
    const val MAX_POINTS = 160
    private const val MAX_EXITS = RoadContextJunctions.MAX_PRIORITIZED_EXITS
    private const val MIN_GPS_ROADS = 6
    private const val MAX_UPCOMING_ROADS = 4

    enum class Role { RING, EXIT, GPS, UPCOMING, EXTRA }
    data class Drawn(val road: MapRoadSegment, val points: List<GeoPointE6>, val role: Role)
    data class Result(
        val drawn: List<Drawn>,
        val slotCounts: Map<Role, Int>,
        val pointCounts: Map<Role, Int>,
        val offRouteGpsRoads: Int,
        val roadsNotDrawn: Int
    ) {
        val slots: Int get() = drawn.size
        val points: Int get() = drawn.sumOf { it.points.size }
        fun count(role: Role) = slotCounts[role] ?: 0
    }

    private class Metric(latitude: Double) {
        private val xScale = 111_320.0 * cos(Math.toRadians(latitude))
        fun coords(p: GeoPointE6, origin: GeoPointE6) = Pair(
            (p.longitudeE6.toDouble() - origin.longitudeE6) / 1e6 * xScale,
            (p.latitudeE6.toDouble() - origin.latitudeE6) / 1e6 * 111_320.0)
        fun length(a: GeoPointE6, b: GeoPointE6): Double {
            val (dx, dy) = coords(a, b)
            return hypot(dx, dy)
        }
        fun distance(p: GeoPointE6, points: List<GeoPointE6>): Double {
            if (points.isEmpty()) return Double.POSITIVE_INFINITY
            if (points.size == 1) return length(p, points.first())
            var best = Double.POSITIVE_INFINITY
            for (i in 0 until points.lastIndex) {
                val (ax, ay) = coords(points[i], p)
                val (bx, by) = coords(points[i + 1], p)
                val dx = bx - ax
                val dy = by - ay
                val len = dx * dx + dy * dy
                val t = if (len > 1e-6)
                    (-(ax * dx + ay * dy) / len).coerceIn(0.0, 1.0) else 0.0
                best = minOf(best, hypot(ax + t * dx, ay + t * dy))
            }
            return best
        }
    }

    fun select(
        roads: List<MapRoadSegment>, focus: RoadContextJunctions.Priority?,
        latitude: Double, longitude: Double,
        upcoming: GeoPointE6?, route: List<GeoPointE6> = emptyList()
    ): Result {
        val metric = Metric(latitude)
        val gps = GeoPointE6((latitude * 1e6).roundToInt(),
            (longitude * 1e6).roundToInt())
        val ringMembers = focus?.members?.toSet().orEmpty()
        val available = roads.filter { it !in ringMembers }
        val gpsOrder = available.sortedBy { metric.distance(gps, it.points) }
        val upcomingOrder = if (upcoming != null) {
            available.sortedBy { metric.distance(upcoming, it.points) }
        } else emptyList()
        // LinkedHashMap de-duplicates overlapping GPS/focus/upcoming lists
        // without silently consuming the scarce 20 BLE slots.
        val priority = LinkedHashMap<MapRoadSegment, Role>()
        fun add(road: MapRoadSegment, role: Role) {
            if (priority.size < MAX_ROADS) priority.putIfAbsent(road, role)
        }
        focus?.ring?.let { add(it, Role.RING) }
        focus?.exits?.take(MAX_EXITS)?.forEach { add(it, Role.EXIT) }
        // This reserve is independent of the route, even when GPS is off-route.
        gpsOrder.asSequence().filterNot { it in priority }.take(MIN_GPS_ROADS)
            .forEach { add(it, Role.GPS) }
        upcomingOrder.asSequence().filterNot { it in priority }.take(MAX_UPCOMING_ROADS)
            .forEach { add(it, Role.UPCOMING) }
        gpsOrder.forEach { add(it, Role.EXTRA) }

        val drawn = ArrayList<Drawn>()
        var remaining = MAX_POINTS
        val items = priority.toList()
        for ((index, item) in items.withIndex()) {
            if (remaining < 2 || drawn.size >= MAX_ROADS) break
            val road = item.first
            val role = item.second
            // Leave two vertices for every later reserved road; the ring cannot
            // spend all 160 points while local context is still outstanding.
            val later = items.size - index - 1
            val allowance = (remaining - 2 * later).coerceAtLeast(2)
            val limit = minOf(allowance, when (role) {
                Role.RING -> 20
                Role.EXIT -> 10
                else -> 8
            })
            val shape = when (role) {
                Role.RING -> RoadContextWindow.closedRing(
                    road.points, latitude, limit, focus?.junctionPoints.orEmpty()
                ).ifEmpty {
                    RoadContextWindow.around(road.points, latitude, longitude, minOf(8, limit))
                }
                Role.EXIT -> {
                    val connection = road.points.firstOrNull { it in focus?.junctionPoints.orEmpty() }
                    RoadContextWindow.around(road.points,
                        connection?.latitudeE6?.div(1e6) ?: latitude,
                        connection?.longitudeE6?.div(1e6) ?: longitude, limit)
                }
                Role.UPCOMING -> RoadContextWindow.around(
                    road.points, upcoming?.latitudeE6?.div(1e6) ?: latitude,
                    upcoming?.longitudeE6?.div(1e6) ?: longitude, limit)
                else -> RoadContextWindow.around(road.points, latitude, longitude, limit)
            }
            if (shape.size < 2) continue
            drawn.add(Drawn(road, shape, role))
            remaining -= shape.size
        }
        val slots = drawn.groupingBy { it.role }.eachCount()
        val points = drawn.groupBy { it.role }.mapValues { (_, values) ->
            values.sumOf { it.points.size }
        }
        val offRoute = if (route.size < 2) 0 else drawn.count {
            it.role == Role.GPS && it.points.minByOrNull { point ->
                metric.length(point, gps)
            }?.let { nearest -> metric.distance(nearest, route) > 20.0 } == true
        }
        return Result(drawn, slots, points, offRoute, items.size - drawn.size)
    }
}

/** Interpolate a useful point AHEAD along the green route, not a random vertex. */
internal object RoadContextUpcoming {
    fun ahead(routeWindow: List<GeoPointE6>, latitude: Double,
              speedMps: Float, accuracyM: Float): GeoPointE6? {
        if (routeWindow.isEmpty()) return null
        if (routeWindow.size == 1) return routeWindow.first()
        val speed = speedMps.takeIf { it.isFinite() }?.coerceIn(0f, 35f) ?: 0f
        val accuracy = accuracyM.takeIf { it.isFinite() }?.coerceIn(0f, 35f) ?: 0f
        val distanceM = (90.0 + speed * 6.0 + accuracy * 0.5).coerceIn(90.0, 220.0)
        val lonScale = 111_320.0 * cos(Math.toRadians(latitude))
        var travelled = 0.0
        for (index in 0 until routeWindow.lastIndex) {
            val a = routeWindow[index]
            val b = routeWindow[index + 1]
            val segment = hypot((b.latitudeE6 - a.latitudeE6) / 1e6 * 111_320.0,
                (b.longitudeE6 - a.longitudeE6) / 1e6 * lonScale)
            if (segment < 0.01) continue
            if (travelled + segment >= distanceM) {
                val t = ((distanceM - travelled) / segment).coerceIn(0.0, 1.0)
                return GeoPointE6(
                    (a.latitudeE6 + (b.latitudeE6 - a.latitudeE6) * t).roundToInt(),
                    (a.longitudeE6 + (b.longitudeE6 - a.longitudeE6) * t).roundToInt()
                )
            }
            travelled += segment
        }
        return routeWindow.last()
    }
}
