package com.motogps.android.map

import com.motogps.android.jni.GeoPointE6
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Identify one credible roundabout-like OSM circuit per scene.
 * Topology only helps DISPLAY selection: never changes routing or road access.
 * In particular, road.layer is deliberately NOT used.
 */
internal object RoadContextJunctions {
    private const val MIN_SCORE = 70
    private const val MAX_WAYS = 8
    private const val MAX_EDGES = 90
    private const val MAX_SEARCH_STATES = 3500
    private const val MAX_CANDIDATES = 48

    private class Metric(latitude: Double) {
        private val x = 111_320.0 * cos(Math.toRadians(latitude))
        private val y = 111_320.0
        fun squared(a: GeoPointE6, b: GeoPointE6): Double {
            val dy = (a.latitudeE6.toDouble() - b.latitudeE6) / 1e6 * y
            val dx = (a.longitudeE6.toDouble() - b.longitudeE6) / 1e6 * x
            return dx * dx + dy * dy
        }
        fun distance(a: GeoPointE6, b: GeoPointE6) = sqrt(squared(a, b))
        fun toXY(p: GeoPointE6, origin: GeoPointE6) =
            Pair((p.longitudeE6.toDouble() - origin.longitudeE6) / 1e6 * x,
                (p.latitudeE6.toDouble() - origin.latitudeE6) / 1e6 * y)
        fun segmentSquared(p: GeoPointE6, a: GeoPointE6, b: GeoPointE6): Double {
            val (ax, ay) = toXY(a, p)
            val (bx, by) = toXY(b, p)
            val dx = bx - ax
            val dy = by - ay
            val len = dx * dx + dy * dy
            val t = if (len > 1e-8) (-(ax * dx + ay * dy) / len).coerceIn(0.0, 1.0)
                else 0.0
            val px = ax + t * dx
            val py = ay + t * dy
            return px * px + py * py
        }
        fun nearest(points: List<GeoPointE6>, p: GeoPointE6): Double {
            if (points.isEmpty()) return Double.POSITIVE_INFINITY
            if (points.size == 1) return distance(points[0], p)
            var best = Double.POSITIVE_INFINITY
            for (i in 0 until points.lastIndex) {
                best = minOf(best, segmentSquared(p, points[i], points[i + 1]))
            }
            return sqrt(best)
        }
    }

    data class Priority(
        val ring: MapRoadSegment,
        val members: List<MapRoadSegment>,
        val exits: List<MapRoadSegment>,
        val junctionPoints: Set<GeoPointE6>,
        val confidence: Int,
        val gpsDistanceM: Double,
        val upcomingDistanceM: Double,
        val key: String
    )
    data class Analysis(
        val focus: Priority?,
        val candidates: Int,
        val qualified: Int,
        val excluded: Map<String, Int>
    )

    private data class Edge(
        val road: MapRoadSegment, val from: Long, val to: Long,
        val lengthM: Double
    )
    private data class Step(val index: Int, val forward: Boolean)

    private fun length(points: List<GeoPointE6>, metric: Metric): Double =
        points.zipWithNext().sumOf { (a, b) -> metric.distance(a, b) }

    private fun validLoop(points: List<GeoPointE6>, metric: Metric): Boolean {
        if (points.size < 4 || metric.squared(points.first(), points.last()) > 6.25) return false
        val total = length(points, metric)
        if (total !in 35.0..550.0) return false
        val origin = points[0]
        val coords = points.map { metric.toXY(it, origin) }
        val width = coords.maxOf { it.first } - coords.minOf { it.first }
        val height = coords.maxOf { it.second } - coords.minOf { it.second }
        val diameter = hypot(width, height)
        if (diameter !in 12.0..160.0) return false
        var twiceArea = 0.0
        for (i in 0 until coords.lastIndex) {
            twiceArea += coords[i].first * coords[i + 1].second -
                coords[i + 1].first * coords[i].second
        }
        val compactness = 2.0 * PI * kotlin.math.abs(twiceArea) / (total * total)
        // Exclude U-turns, degenerate and extremely elongated loops.
        return compactness in 0.17..1.06
    }

    fun isClosedRing(road: MapRoadSegment): Boolean {
        val points = road.points
        if (points.size < 4) return false
        val metric = Metric(points.first().latitudeE6 / 1e6)
        val topoClosed = road.nodeIds.size == points.size &&
            road.nodeIds.first() > 0 && road.nodeIds.first() == road.nodeIds.last()
        if (!topoClosed && metric.squared(points.first(), points.last()) > 6.25) return false
        return validLoop(points, metric)
    }

    fun find(roads: List<MapRoadSegment>, latitude: Double, longitude: Double,
             upcoming: GeoPointE6?): Priority? =
        analyze(roads, latitude, longitude, upcoming).focus

    fun analyze(roads: List<MapRoadSegment>, latitude: Double, longitude: Double,
                upcoming: GeoPointE6?): Analysis {
        if (roads.isEmpty() || !latitude.isFinite() || !longitude.isFinite())
            return Analysis(null, 0, 0, emptyMap())
        val metric = Metric(latitude)
        val gps = GeoPointE6((latitude * 1e6).toInt(), (longitude * 1e6).toInt())
        val reasons = linkedMapOf<String, Int>()
        fun reject(reason: String) {
            reasons[reason] = (reasons[reason] ?: 0) + 1
        }
        var candidates = 0
        var qualified = 0
        var best: Priority? = null
        val seen = HashSet<String>()

        fun candidate(members: List<MapRoadSegment>, points: List<GeoPointE6>,
                      nodeIds: List<Long>, key: String) {
            if (candidates >= MAX_CANDIDATES || !seen.add(key)) return
            candidates++
            if (!validLoop(points, metric)) {
                reject("shape")
                return
            }
            val gpsDistance = metric.nearest(points, gps)
            val aheadDistance = upcoming?.let { metric.nearest(points, it) }
                ?: Double.POSITIVE_INFINITY
            if (gpsDistance > 270.0 && aheadDistance > 180.0) {
                reject("far")
                return
            }
            val memberSet = members.toSet()
            val ringNodeIds = if (nodeIds.size == points.size)
                nodeIds.filter { it > 0L }.toHashSet() else hashSetOf<Long>()
            val exits = roads.asSequence().filter { road ->
                road !in memberSet && road.nodeIds.size == road.points.size &&
                    road.nodeIds.any { it > 0L && it in ringNodeIds }
            }.distinct().sortedBy { road ->
                val linked = road.points.filterIndexed { index, _ ->
                    road.nodeIds[index] in ringNodeIds
                }
                linked.minOfOrNull { metric.nearest(points, it) }
                    ?: Double.POSITIVE_INFINITY
            }.take(8).toList()
            val attachedIds = exits.flatMap { road ->
                road.nodeIds.filter { it > 0L && it in ringNodeIds }
            }.toHashSet()
            val protected = if (nodeIds.size == points.size) {
                points.filterIndexed { index, _ -> nodeIds[index] in attachedIds }.toSet()
            } else emptySet()

            val topological = nodeIds.size == points.size && nodeIds.firstOrNull()?.let {
                it > 0L && it == nodeIds.last()
            } == true
            val close = metric.squared(points.first(), points.last()) <= 2.25
            val closeEnough = gpsDistance <= 130.0 || aheadDistance <= 150.0
            val connectionScore = when {
                exits.size >= 3 -> 20
                exits.size == 2 -> 14
                exits.size == 1 -> 6
                else -> 0
            }
            val classScore = if (members.any { it.roadClass == 0 }) -20 else 10
            val scarcityPenalty = when (exits.size) {
                0 -> 30
                1 -> 12
                else -> 0
            }
            val score = (if (topological) 30 else 10) +
                (if (close) 10 else 0) + 15 +
                (if (closeEnough) 15 else 8) + connectionScore +
                classScore - scarcityPenalty
            if (score < MIN_SCORE) {
                reject("confidence")
                return
            }
            qualified++
            val ring = if (members.size == 1) members.first() else MapRoadSegment(
                roadClass = members.minOf { it.roadClass },
                maxSpeedKph = members.minOf { it.maxSpeedKph },
                minLatE6 = points.minOf { it.latitudeE6 },
                minLonE6 = points.minOf { it.longitudeE6 },
                maxLatE6 = points.maxOf { it.latitudeE6 },
                maxLonE6 = points.maxOf { it.longitudeE6 },
                points = points, nodeIds = nodeIds
            )
            val option = Priority(ring, members, exits, protected, score,
                gpsDistance, aheadDistance, key)
            val previous = best
            if (previous == null || option.confidence > previous.confidence ||
                (option.confidence == previous.confidence &&
                    minOf(gpsDistance, aheadDistance) <
                    minOf(previous.gpsDistanceM, previous.upcomingDistanceM))) {
                best = option
            }
        }

        // First, recognize complete single-way loops. Without OSM IDs the ring
        // can still be drawn in normal context but is unlikely to pass scoring.
        for (road in roads) {
            if (candidates >= MAX_CANDIDATES) break
            if (isClosedRing(road)) {
                candidate(listOf(road), road.points, road.nodeIds,
                    "s:" + (road.osmWayId?.toString() ?: road.points.first().toString()))
            }
        }

        // Build a bounded graph of way ENDPOINT nodes. OSM often splits a
        // roundabout at each exit, so no individual way is itself a closed loop.
        val edges = roads.asSequence().mapNotNull { road ->
            val ids = road.nodeIds
            if (road.points.size < 2 || ids.size != road.points.size ||
                ids.first() <= 0L || ids.last() <= 0L || ids.first() == ids.last()) return@mapNotNull null
            val distance = minOf(metric.nearest(road.points, gps),
                upcoming?.let { metric.nearest(road.points, it) } ?: Double.POSITIVE_INFINITY)
            if (distance > 300.0) return@mapNotNull null
            val meters = length(road.points, metric)
            if (meters !in 3.0..300.0) return@mapNotNull null
            Edge(road, ids.first(), ids.last(), meters) to distance
        }.sortedBy { it.second }.take(MAX_EDGES).map { it.first }.toList()

        val adjacency = HashMap<Long, MutableList<Int>>()
        edges.forEachIndexed { index, edge ->
            adjacency.getOrPut(edge.from) { ArrayList() }.add(index)
            adjacency.getOrPut(edge.to) { ArrayList() }.add(index)
        }

        fun assemble(path: List<Step>) {
            val indices = path.map { it.index }
            val signature = "m:" + indices.sorted().joinToString(",")
            if (signature in seen) return
            val combinedPoints = ArrayList<GeoPointE6>()
            val combinedIds = ArrayList<Long>()
            val members = ArrayList<MapRoadSegment>()
            for (step in path) {
                val road = edges[step.index].road
                val orientedPoints = if (step.forward) road.points else road.points.asReversed()
                val orientedIds = if (step.forward) road.nodeIds else road.nodeIds.asReversed()
                if (combinedIds.isNotEmpty()) {
                    if (combinedIds.last() != orientedIds.first() ||
                        metric.squared(combinedPoints.last(), orientedPoints.first()) > 6.25) return
                    combinedPoints.addAll(orientedPoints.drop(1))
                    combinedIds.addAll(orientedIds.drop(1))
                } else {
                    combinedPoints.addAll(orientedPoints)
                    combinedIds.addAll(orientedIds)
                }
                members.add(road)
            }
            if (combinedIds.firstOrNull() != combinedIds.lastOrNull() ||
                metric.squared(combinedPoints.first(), combinedPoints.last()) > 6.25) return
            // OSM IDs prove closure; normalize sub-metre coordinate rounding.
            combinedPoints[combinedPoints.lastIndex] = combinedPoints.first()
            candidate(members, combinedPoints, combinedIds, signature)
        }

        var searchStates = 0
        val path = ArrayList<Step>()
        val used = HashSet<Int>()
        val visitedNodes = HashSet<Long>()
        fun walk(startNode: Long, current: Long, distance: Double) {
            if (searchStates >= MAX_SEARCH_STATES || candidates >= MAX_CANDIDATES ||
                distance > 550.0 || path.size > MAX_WAYS) return
            searchStates++
            if (current == startNode) {
                if (path.size >= 2) assemble(path)
                return
            }
            if (path.size == MAX_WAYS) return
            for (edgeIndex in adjacency[current].orEmpty()) {
                if (edgeIndex in used) continue
                val edge = edges[edgeIndex]
                val forward = edge.from == current
                val next = if (forward) edge.to else edge.from
                if (next != startNode && next in visitedNodes) continue
                val newDistance = distance + edge.lengthM
                if (newDistance > 550.0) continue
                used.add(edgeIndex)
                path.add(Step(edgeIndex, forward))
                if (next != startNode) visitedNodes.add(next)
                walk(startNode, next, newDistance)
                if (next != startNode) visitedNodes.remove(next)
                path.removeAt(path.lastIndex)
                used.remove(edgeIndex)
                if (searchStates >= MAX_SEARCH_STATES || candidates >= MAX_CANDIDATES) break
            }
        }

        for (index in edges.indices) {
            if (searchStates >= MAX_SEARCH_STATES || candidates >= MAX_CANDIDATES) break
            val edge = edges[index]
            path.clear()
            used.clear()
            visitedNodes.clear()
            path.add(Step(index, true))
            used.add(index)
            visitedNodes.add(edge.from)
            visitedNodes.add(edge.to)
            walk(edge.from, edge.to, edge.lengthM)
        }
        if (searchStates >= MAX_SEARCH_STATES) reject("search_limit")
        return Analysis(best, candidates, qualified, reasons)
    }
}
