package com.motogps.android.map

import com.motogps.android.jni.GeoPointE6
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Reserve complete small OSM loops and their actual node-connected exits.
 * This is a DISPLAY priority, not a routing or legal-access decision.
 * No road is removed: normal local roads remain available outside the route.
 */
internal object RoadContextJunctions {
    private fun distance(a: GeoPointE6, b: GeoPointE6): Double {
        val lat = (a.latitudeE6 + b.latitudeE6) / 2e6
        return hypot((a.latitudeE6 - b.latitudeE6) / 1e6 * 111_320.0,
            (a.longitudeE6 - b.longitudeE6) / 1e6 *
                111_320.0 * cos(Math.toRadians(lat)))
    }

    /** True only for compact geometrically closed OSM ways, not generic bends. */
    fun isClosedRing(road: MapRoadSegment): Boolean {
        val pts = road.points
        if (pts.size < 4) return false
        val nodeClosure = road.nodeIds.size == pts.size &&
            road.nodeIds.firstOrNull()?.let { it > 0L && it == road.nodeIds.last() } == true
        if (!nodeClosure && distance(pts.first(), pts.last()) > 2.5) return false
        // Reject huge motorway rings and tiny degenerate loops.
        val center = GeoPointE6(
            pts.dropLast(1).map { it.latitudeE6.toLong() }.average().toInt(),
            pts.dropLast(1).map { it.longitudeE6.toLong() }.average().toInt())
        val diameter = pts.maxOf { distance(it, center) } * 2.0
        val length = pts.zipWithNext().sumOf { (a, b) -> distance(a, b) }
        return diameter in 12.0..160.0 && length in 35.0..550.0
    }

    data class Priority(val ring: MapRoadSegment, val exits: List<MapRoadSegment>)

    fun find(roads: List<MapRoadSegment>, latitude: Double, longitude: Double,
             upcoming: GeoPointE6?): Priority? {
        if (roads.isEmpty()) return null
        val gps = GeoPointE6((latitude * 1e6).toInt(), (longitude * 1e6).toInt())
        val rings = roads.filter(::isClosedRing)
        val ring = rings.mapNotNull { candidate ->
            val pts = candidate.points
            val nearestGps = pts.minOf { distance(it, gps) }
            val nearestAhead = upcoming?.let { ahead -> pts.minOf { distance(it, ahead) } }
                ?: Double.POSITIVE_INFINITY
            if (nearestGps > 270.0 && nearestAhead > 180.0) null
            else candidate to minOf(nearestGps, nearestAhead)
        }.minByOrNull { it.second }?.first ?: return null

        val ids = if (ring.nodeIds.size == ring.points.size) ring.nodeIds.toSet()
            else emptySet()
        val exits = roads.asSequence().filter { road ->
            road !== ring &&
                ids.isNotEmpty() &&
                road.nodeIds.any { it > 0L && it in ids }
        }.sortedBy { road ->
            road.points.minOf { p -> ring.points.minOf { rp -> distance(p, rp) } }
        }.take(8).toList()
        return Priority(ring, exits)
    }
}
