package com.motogps.android.map

import com.motogps.android.jni.GeoPointE6
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Conservative display-only fallback for OpenFreeMap vector tiles.
 *
 * MVT transportation polylines do not carry original OSM node/way IDs.
 * Only connect near-identical ENDPOINT coordinates; never infer crossings,
 * grade separation, turn permissions, or route access.
 *
 * MotoMap v4 topology remains authoritative and is never modified.
 */
internal object OpenFreeMapRoundaboutGeometry {
    private const val JOIN_METERS = 1.25
    private const val MAX_EDGES = 80
    private const val MAX_DEPTH = 12
    private const val MAX_STATES = 2800
    private const val MAX_CIRCUITS = 32
    private const val MAX_PERIMETER_M = 450.0
    private const val MAX_FOCUS_DISTANCE_M = 180.0

    data class Analysis(
        val focus: RoadContextJunctions.Priority?,
        val edges: Int,
        val circuits: Int,
        val rejected: Int
    )

    private class Metric(latitude: Double) {
        private val x = 111320.0 * cos(Math.toRadians(latitude))
        fun distance(a: GeoPointE6, b: GeoPointE6) = hypot(
            (a.longitudeE6.toDouble() - b.longitudeE6) / 1e6 * x,
            (a.latitudeE6.toDouble() - b.latitudeE6) / 1e6 * 111320.0
        )
        fun lineLength(points: List<GeoPointE6>): Double =
            points.zipWithNext().sumOf { (a, b) -> distance(a, b) }
        fun closest(points: List<GeoPointE6>, point: GeoPointE6): Double {
            var best = Double.POSITIVE_INFINITY
            for ((a, b) in points.zipWithNext()) {
                val ax = (a.longitudeE6.toDouble() - point.longitudeE6) / 1e6 * x
                val ay = (a.latitudeE6.toDouble() - point.latitudeE6) / 1e6 * 111320.0
                val bx = (b.longitudeE6.toDouble() - point.longitudeE6) / 1e6 * x
                val by = (b.latitudeE6.toDouble() - point.latitudeE6) / 1e6 * 111320.0
                val dx = bx - ax
                val dy = by - ay
                val t = if (dx * dx + dy * dy < 1e-8) 0.0
                    else (-(ax * dx + ay * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
                best = minOf(best, hypot(ax + t * dx, ay + t * dy))
            }
            return best
        }
        fun circularity(points: List<GeoPointE6>): Double {
            val length = lineLength(points)
            if (length < 1.0) return 0.0
            val origin = points.first()
            val coords = points.map { point ->
                ((point.longitudeE6.toDouble() - origin.longitudeE6) / 1e6 * x) to
                    ((point.latitudeE6.toDouble() - origin.latitudeE6) / 1e6 * 111320.0)
            }
            var area = 0.0
            for (i in 0 until coords.lastIndex) {
                area += coords[i].first * coords[i + 1].second -
                    coords[i + 1].first * coords[i].second
            }
            return 2.0 * PI * abs(area) / (length * length)
        }
    }

    private data class Arc(val road: MapRoadSegment, val a: Int, val b: Int, val meters: Double)
    private data class Step(val index: Int, val forward: Boolean)

    fun analyze(roads: List<MapRoadSegment>, latitude: Double, longitude: Double,
                upcoming: GeoPointE6?): Analysis {
        if (!latitude.isFinite() || !longitude.isFinite()) return Analysis(null, 0, 0, 0)
        val gps = GeoPointE6((latitude * 1e6).roundToInt(),
                            (longitude * 1e6).roundToInt())
        val metric = Metric(latitude)
        // Do not even consider tagged MotoMap data in this fallback.
        val candidates = roads.asSequence().filter { road ->
            road.osmWayId == null && road.nodeIds.isEmpty() && road.roadClass in 1..3 &&
                road.points.size in 2..150 &&
                metric.lineLength(road.points) in 1.0..MAX_PERIMETER_M &&
                (metric.closest(road.points, gps) <= 220.0 ||
                    (upcoming != null && metric.closest(road.points, upcoming) <= 200.0))
        }.sortedBy { metric.closest(it.points, gps) }.take(MAX_EDGES).toList()
        if (candidates.isEmpty()) return Analysis(null, 0, 0, 0)

        // Coordinate clustering is confined to endpoints, with a tight tolerance.
        val endpoints = ArrayList<GeoPointE6>()
        fun nodeId(p: GeoPointE6): Int {
            val matched = endpoints.indexOfFirst { metric.distance(it, p) <= JOIN_METERS }
            if (matched >= 0) return matched
            endpoints.add(p)
            return endpoints.lastIndex
        }
        val arcs = candidates.mapNotNull { road ->
            val a = nodeId(road.points.first())
            val b = nodeId(road.points.last())
            if (a == b && road.points.size < 4) return@mapNotNull null
            Arc(road, a, b, metric.lineLength(road.points))
        }
        val adjacency = HashMap<Int, MutableList<Int>>()
        arcs.forEachIndexed { i, arc ->
            adjacency.getOrPut(arc.a) { ArrayList() }.add(i)
            if (arc.b != arc.a) adjacency.getOrPut(arc.b) { ArrayList() }.add(i)
        }
        val seen = HashSet<String>()
        var circuits = 0
        var rejected = 0
        var best: RoadContextJunctions.Priority? = null
        var states = 0
        val used = HashSet<Int>()
        val visitedNodes = HashSet<Int>()
        val path = ArrayList<Step>()

        fun consider() {
            val members = path.map { arcs[it.index].road }
            val signature = path.map { it.index }.sorted().joinToString(",")
            if (!seen.add(signature)) return
            circuits++
            val shape = ArrayList<GeoPointE6>()
            for (step in path) {
                val points = arcs[step.index].road.points
                    .let { if (step.forward) it else it.asReversed() }
                if (shape.isNotEmpty() && metric.distance(shape.last(), points.first()) > JOIN_METERS) {
                    rejected++
                    return
                }
                if (shape.isEmpty()) shape.addAll(points) else shape.addAll(points.drop(1))
            }
            if (shape.size < 5 || metric.distance(shape.first(), shape.last()) > JOIN_METERS) {
                rejected++
                return
            }
            shape[shape.lastIndex] = shape.first()
            val perimeter = metric.lineLength(shape)
            val width = (shape.maxOf { it.longitudeE6 } - shape.minOf { it.longitudeE6 }) /
                1e6 * 111320.0 * cos(Math.toRadians(latitude))
            val height = (shape.maxOf { it.latitudeE6 } - shape.minOf { it.latitudeE6 }) /
                1e6 * 111320.0
            val diameter = hypot(width, height)
            val roundness = metric.circularity(shape)
            if (perimeter !in 35.0..MAX_PERIMETER_M || diameter !in 12.0..145.0 ||
                roundness !in 0.82..1.06 || members.any { it.roadClass == 0 }) {
                rejected++
                return
            }
            val distance = metric.closest(shape, gps)
            val ahead = upcoming?.let { metric.closest(shape, it) } ?: Double.POSITIVE_INFINITY
            if (distance > MAX_FOCUS_DISTANCE_M && ahead > 150.0) {
                rejected++
                return
            }
            // Only branches with an endpoint at a ring vertex are safe enough.
            // A line that merely crosses the ring in 2D is NOT an exit.
            val memberSet = members.toHashSet()
            val attachments = LinkedHashMap<MapRoadSegment, GeoPointE6>()
            for (road in candidates) {
                if (road in memberSet || road.points.size < 2) continue
                val endpoint = listOf(road.points.first(), road.points.last())
                    .firstOrNull { end -> shape.any { metric.distance(it, end) <= JOIN_METERS } }
                    ?: continue
                val farEnd = if (endpoint == road.points.first())
                    road.points.last() else road.points.first()
                if (metric.closest(shape, farEnd) < 9.0) continue
                val snap = shape.minBy { metric.distance(it, endpoint) }
                attachments[road] = snap
            }
            // A genuine ring must have multiple connected approaches.
            val grouped = attachments.entries.groupBy { (_, point) ->
                shape.indexOf(point)
            }
            if (grouped.size < 3) {
                rejected++
                return
            }
            val exits = grouped.values.map { branches ->
                branches.minBy { (_, point) -> metric.distance(point, gps) }
            }.sortedBy { metric.closest(it.key.points, gps) }
                .take(RoadContextJunctions.MAX_PRIORITIZED_EXITS)
            val combined = MapRoadSegment(
                roadClass = members.minOf { it.roadClass },
                maxSpeedKph = members.map { it.maxSpeedKph }.filter { it > 0 }.minOrNull() ?: 0,
                minLatE6 = shape.minOf { it.latitudeE6 },
                minLonE6 = shape.minOf { it.longitudeE6 },
                maxLatE6 = shape.maxOf { it.latitudeE6 },
                maxLonE6 = shape.maxOf { it.longitudeE6 },
                points = shape
            )
            val focus = RoadContextJunctions.Priority(
                combined, members, exits.map { it.key },
                exits.map { it.value }.toSet(), 75 + exits.size,
                distance, ahead,
                "ofm:" + shape.minOf { it.latitudeE6 } + ":" +
                    shape.minOf { it.longitudeE6 } + ":" + perimeter.roundToInt(),
                attachments.size, grouped.size,
                circularity = roundness, perimeterM = perimeter
            )
            if (best == null || focus.gpsDistanceM < best!!.gpsDistanceM) best = focus
        }

        fun walk(start: Int, current: Int, length: Double) {
            if (++states > MAX_STATES || circuits >= MAX_CIRCUITS ||
                path.size > MAX_DEPTH || length > MAX_PERIMETER_M) return
            if (current == start) {
                consider()
                return
            }
            if (path.size == MAX_DEPTH) return
            for (index in adjacency[current].orEmpty()) {
                if (index in used) continue
                val arc = arcs[index]
                val next = if (arc.a == current) arc.b else arc.a
                if (next != start && next in visitedNodes) continue
                used.add(index)
                if (next != start) visitedNodes.add(next)
                path.add(Step(index, arc.a == current))
                walk(start, next, length + arc.meters)
                path.removeAt(path.lastIndex)
                if (next != start) visitedNodes.remove(next)
                used.remove(index)
                if (states >= MAX_STATES || circuits >= MAX_CIRCUITS) break
            }
        }
        arcs.forEachIndexed { index, arc ->
            if (states >= MAX_STATES || circuits >= MAX_CIRCUITS) return@forEachIndexed
            used.add(index)
            visitedNodes.add(arc.a)
            visitedNodes.add(arc.b)
            path.add(Step(index, true))
            walk(arc.a, arc.b, arc.meters)
            path.clear()
            used.clear()
            visitedNodes.clear()
        }
        return Analysis(best, arcs.size, circuits, rejected)
    }
}
