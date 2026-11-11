package com.motogps.android.viewmodel

import com.motogps.android.jni.GeoPointE6
import com.motogps.android.map.MapRoadSegment
import com.motogps.android.map.RoadContextJunctions
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Explicit BLE scene allocation. Focus + exits have priority; six independent
 * GPS-local roads stay reserved, with two of those slots favouring genuine
 * near-route context when the motorcycle is on the route.
 * A single ring and up to seven distinct outgoing branches take precedence
 * over less relevant local streets (20 roads / 160 points total).
 * All geometry is chosen from MotoMap, never restricted to the calculated
 * route: the rider must still see streets during a deviation.
 */
internal object RoadContextSceneSelector {
    const val MAX_ROADS = 20
    const val MAX_POINTS = 160
    private const val MAX_EXITS = RoadContextJunctions.MAX_PRIORITIZED_EXITS
    private const val MIN_GPS_ROADS = 6
    private const val MAX_UPCOMING_ROADS = 6

    // A lane near the next manoeuvre must outrank unrelated lanes just behind
    // the rider; six local GPS roads remain protected when navigating off-route.
    private const val MAX_FORWARD_DISTANCE_M = 58.0
    private data class ForwardRoad(val road: MapRoadSegment,
        val anchor: GeoPointE6, val score: Double, val forwardM: Double)

    enum class Role { RING, EXIT, GPS, UPCOMING, EXTRA }
    data class Drawn(val road: MapRoadSegment, val points: List<GeoPointE6>, val role: Role)
    data class Result(
        val drawn: List<Drawn>,
        val slotCounts: Map<Role, Int>,
        val pointCounts: Map<Role, Int>,
        val offRouteGpsRoads: Int,
        val roadsNotDrawn: Int,
        val nearRouteRoadsDrawn: Int = 0,
        val secondaryClosedRings: Int = 0
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
        // The old forward probes started 90m ahead. On a small ring the
        // unprotected 0-90m corridor can contain almost the entire junction.
        // Preserve four nearest GPS roads plus two independent near-route
        // roads (when actually on-route), never suppress the off-route map.
        val nearRouteProbes = if (route.size >= 2 &&
            metric.distance(gps, route) <= 25.0) {
            RoadContextUpcoming.probes(route, latitude)
                .filter { (metres, _) -> metres <= 75.0 }
                .map { it.second }
        } else emptyList()
        val nearRouteOrder = if (nearRouteProbes.isNotEmpty()) {
            available.asSequence().filter { road ->
                metric.distance(gps, road.points) <= 95.0 &&
                    nearRouteProbes.any { probe ->
                        metric.distance(probe, road.points) <= 13.0
                    }
            }.sortedBy { road ->
                metric.distance(gps, road.points) +
                    road.roadClass.coerceIn(0, 4) * 1.5
            }.toList()
        } else emptyList()
        // Sample the *forward route corridor*, not a single point 90-220m
        // ahead. A long OSM way must later be clipped at its own junction,
        // otherwise the chosen exit can remain outside its transmitted window.
        // Do not privilege a stale green route when the real GPS is off-route.
        val forwardProbes = if (route.size >= 2 &&
            metric.distance(gps, route) <= 80.0) {
            RoadContextUpcoming.probes(route, latitude)
        } else emptyList()
        val forwardRoads = if (forwardProbes.isNotEmpty()) {
            available.mapNotNull { road ->
                val best = forwardProbes.map { (meters, point) ->
                    Triple(metric.distance(point, road.points), meters, point)
                }.minBy { it.first + it.second * 0.015 }
                if (best.first > MAX_FORWARD_DISTANCE_M) null
                else ForwardRoad(road, best.third, best.first + best.second * 0.015 +
                    road.roadClass.coerceIn(0, 4) * 1.5, best.second)
            }.sortedBy { it.score }
        } else emptyList()
        val forwardAnchor = forwardRoads.associate { it.road to it.anchor }
        val upcomingOrder = when {
            forwardRoads.isNotEmpty() -> {
                // Immediate 20-75m probes must not crowd out junctions
                // 90-500m ahead. Protect three nearby and three farther
                // candidates before returning to the overall score order.
                val near = forwardRoads.filter { it.forwardM < 90.0 }
                val far = forwardRoads.filter { it.forwardM >= 90.0 }
                (near.take(3) + far.take(3) + forwardRoads)
                    .distinctBy { it.road }.map { it.road }
            }
            upcoming != null -> available.sortedBy { metric.distance(upcoming, it.points) }
            else -> emptyList()
        }
        // LinkedHashMap de-duplicates overlapping GPS/focus/upcoming lists
        // without silently consuming the scarce 20 BLE slots.
        val priority = LinkedHashMap<MapRoadSegment, Role>()
        fun add(road: MapRoadSegment, role: Role) {
            if (priority.size < MAX_ROADS) priority.putIfAbsent(road, role)
        }
        focus?.ring?.let { add(it, Role.RING) }
        focus?.exits?.take(MAX_EXITS)?.forEach { add(it, Role.EXIT) }
        // Retain the six-road GPS reserve, but trade at most two purely
        // proximity-ranked slots for streets on the immediate route.
        // No active/credible route: use the existing six nearest roads.
        val gpsReserve = LinkedHashSet<MapRoadSegment>()
        gpsOrder.asSequence().filterNot { it in priority }.take(
            if (nearRouteOrder.isEmpty()) MIN_GPS_ROADS else 4
        ).forEach { gpsReserve.add(it) }
        if (nearRouteOrder.isNotEmpty()) {
            nearRouteOrder.asSequence().filterNot { it in priority || it in gpsReserve }
                .take(2).forEach { gpsReserve.add(it) }
        }
        for (road in gpsOrder) {
            if (gpsReserve.size >= MIN_GPS_ROADS) break
            if (road !in priority) gpsReserve.add(road)
        }
        gpsReserve.forEach { add(it, Role.GPS) }
        upcomingOrder.asSequence().filterNot { it in priority }.take(
            if (forwardRoads.isEmpty()) 4 else MAX_UPCOMING_ROADS
        ).forEach { add(it, Role.UPCOMING) }
        // Use remaining slots for other streets on the upcoming route before
        // filling with less relevant streets around the current GPS position.
        forwardRoads.forEach { add(it.road, Role.EXTRA) }
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
            // A closed OSM circuit must remain closed even when it is NOT
            // the selected focus ring. Otherwise generic around() clips its
            // 10-20 vertices to an open 8-point fragment.
            val secondaryClosedRing = role != Role.RING &&
                role != Role.EXIT && RoadContextJunctions.isClosedRing(road)
            val limit = minOf(allowance, when (role) {
                Role.RING -> 20
                Role.EXIT -> 10
                else -> if (secondaryClosedRing) 16 else 8
            })
            val shape = if (secondaryClosedRing && limit >= 4) {
                RoadContextWindow.closedRing(road.points, latitude, limit)
            } else when (role) {
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
                Role.UPCOMING -> {
                    val anchor = forwardAnchor[road] ?: upcoming
                    RoadContextWindow.around(road.points,
                        anchor?.latitudeE6?.div(1e6) ?: latitude,
                        anchor?.longitudeE6?.div(1e6) ?: longitude, limit)
                }
                Role.EXTRA -> {
                    val anchor = forwardAnchor[road]
                    RoadContextWindow.around(road.points,
                        anchor?.latitudeE6?.div(1e6) ?: latitude,
                        anchor?.longitudeE6?.div(1e6) ?: longitude, limit)
                }
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
        val nearRouteDrawn = drawn.count { it.road in nearRouteOrder }
        val secondaryClosed = drawn.count { item ->
            item.role != Role.RING && item.points.size >= 4 &&
                item.points.first() == item.points.last() &&
                RoadContextJunctions.isClosedRing(item.road)
        }
        return Result(drawn, slots, points, offRoute, items.size - drawn.size,
            nearRouteDrawn, secondaryClosed)
    }
}

/** Interpolate a useful point AHEAD along the green route, not a random vertex. */
internal object RoadContextUpcoming {
    /** Sparse, bounded ahead samples favour visible exits before the next turn. */
    fun probes(routeWindow: List<GeoPointE6>, latitude: Double
    ): List<Pair<Double, GeoPointE6>> {
        if (routeWindow.size < 2) return emptyList()
        val lonScale = 111_320.0 * cos(Math.toRadians(latitude))
        val lengthM = routeWindow.zipWithNext().sumOf { (a, b) ->
            hypot((b.latitudeE6 - a.latitudeE6) / 1e6 * 111_320.0,
                (b.longitudeE6 - a.longitudeE6) / 1e6 * lonScale)
        }
        if (lengthM < 35.0) return emptyList()
        // Keep the next 20-75m visible through a turn/roundabout before
        // reserving distant lanes. This is still a bounded sparse set.
        return listOf(20.0, 45.0, 75.0, 90.0, 160.0,
            240.0, 320.0, 420.0, 500.0)
            .filter { it <= lengthM }
            .ifEmpty { listOf(lengthM) }
            .mapNotNull { metres ->
                atDistance(routeWindow, latitude, metres)?.let { metres to it }
            }
    }

    fun ahead(routeWindow: List<GeoPointE6>, latitude: Double,
              speedMps: Float, accuracyM: Float): GeoPointE6? {
        if (routeWindow.isEmpty()) return null
        if (routeWindow.size == 1) return routeWindow.first()
        val speed = speedMps.takeIf { it.isFinite() }?.coerceIn(0f, 35f) ?: 0f
        val accuracy = accuracyM.takeIf { it.isFinite() }?.coerceIn(0f, 35f) ?: 0f
        val distanceM = (90.0 + speed * 6.0 + accuracy * 0.5).coerceIn(90.0, 220.0)
        return atDistance(routeWindow, latitude, distanceM)
    }

    private fun atDistance(routeWindow: List<GeoPointE6>, latitude: Double,
                           distanceM: Double): GeoPointE6? {
        if (routeWindow.isEmpty()) return null
        if (routeWindow.size == 1) return routeWindow.first()
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
