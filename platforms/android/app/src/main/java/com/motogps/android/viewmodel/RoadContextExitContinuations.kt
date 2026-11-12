package com.motogps.android.viewmodel

import com.motogps.android.jni.GeoPointE6
import com.motogps.android.map.MapRoadSegment
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Extend one *selected* roundabout exit along unambiguous OSM endpoints.
 * This is display geometry only; routing and junction detection are untouched.
 *
 * Never infer connectivity from coordinate proximity. OpenFreeMap context has
 * no OSM node IDs, so it deliberately cannot be joined by this code.
 */
internal object RoadContextExitContinuations {
    private const val MAX_FOLLOWING_WAYS = 2
    private const val MAX_ADDED_METERS = 240.0
    private const val MAX_BEND_DEGREES = 55.0
    private const val MIN_RUNNER_UP_GAP_DEGREES = 18.0

    data class Extension(
        val points: List<GeoPointE6>,
        val consumedWays: List<MapRoadSegment>
    )
    private data class Candidate(
        val road: MapRoadSegment,
        val outwardPoints: List<GeoPointE6>,
        val endingNodeId: Long,
        val turnDegrees: Double
    )

    fun extend(
        exit: MapRoadSegment,
        roads: List<MapRoadSegment>,
        ringNodeIds: Set<Long>,
        ringMembers: Set<MapRoadSegment>,
        chosenExits: Set<MapRoadSegment>,
        alreadyConsumed: Set<MapRoadSegment>,
        latitude: Double
    ): Extension {
        val unchanged = Extension(exit.points, emptyList())
        if (ringNodeIds.isEmpty() || exit.points.size < 2 ||
            exit.nodeIds.size != exit.points.size || exit.nodeIds.any { it <= 0L })
            return unchanged

        // Only a genuine outward endpoint has an unambiguous orientation.
        // A way crossing the roundabout at two nodes must not be extended.
        val startsOnRing = exit.nodeIds.first() in ringNodeIds
        val endsOnRing = exit.nodeIds.last() in ringNodeIds
        if (startsOnRing == endsOnRing) return unchanged
        val line = (if (startsOnRing) exit.points else exit.points.asReversed()).toMutableList()
        var endingNodeId = if (startsOnRing) exit.nodeIds.last() else exit.nodeIds.first()
        if (endingNodeId in ringNodeIds) return unchanged

        val excluded = HashSet<MapRoadSegment>()
        excluded.addAll(ringMembers)
        excluded.addAll(chosenExits)
        excluded.addAll(alreadyConsumed)
        excluded.add(exit)
        val used = ArrayList<MapRoadSegment>()
        var addedMeters = 0.0
        val longitudeScale = 111_320.0 * cos(Math.toRadians(latitude))

        fun distance(a: GeoPointE6, b: GeoPointE6): Double = hypot(
            (b.latitudeE6.toDouble() - a.latitudeE6) / 1e6 * 111_320.0,
            (b.longitudeE6.toDouble() - a.longitudeE6) / 1e6 * longitudeScale
        )
        fun error(a: GeoPointE6, b: GeoPointE6, c: GeoPointE6): Double {
            val ax = (b.longitudeE6.toDouble() - a.longitudeE6) * longitudeScale
            val ay = (b.latitudeE6.toDouble() - a.latitudeE6) * 111_320.0
            val bx = (c.longitudeE6.toDouble() - b.longitudeE6) * longitudeScale
            val by = (c.latitudeE6.toDouble() - b.latitudeE6) * 111_320.0
            val norm = hypot(ax, ay) * hypot(bx, by)
            if (norm < 1e-9) return 180.0
            return acos(((ax * bx + ay * by) / norm).coerceIn(-1.0, 1.0)) * 180.0 / PI
        }

        repeat(MAX_FOLLOWING_WAYS) {
            val tail = line.last()
            val previous = line.dropLast(1).lastOrNull { distance(it, tail) > 0.8 }
                ?: return@repeat
            val options = roads.mapNotNull { road ->
                if (road in excluded || road.junctionKind != 0 ||
                    road.points.size < 2 || road.nodeIds.size != road.points.size ||
                    road.nodeIds.any { it <= 0L || it in ringNodeIds }) return@mapNotNull null
                val fromStart = road.nodeIds.first() == endingNodeId
                val fromEnd = road.nodeIds.last() == endingNodeId
                if (fromStart == fromEnd) return@mapNotNull null
                val points = if (fromStart) road.points else road.points.asReversed()
                if (points.first() != tail) return@mapNotNull null
                val next = points.drop(1).firstOrNull { distance(tail, it) > 0.8 }
                    ?: return@mapNotNull null
                val turn = error(previous, tail, next)
                if (turn > MAX_BEND_DEGREES) return@mapNotNull null
                Candidate(road, points,
                    if (fromStart) road.nodeIds.last() else road.nodeIds.first(), turn)
            }.sortedBy { it.turnDegrees }
            val best = options.firstOrNull() ?: return@repeat
            // Two nearly aligned possible ways form a real fork; do not
            // fabricate a continuation by guessing which arm is correct.
            if (options.size > 1 &&
                options[1].turnDegrees - best.turnDegrees < MIN_RUNNER_UP_GAP_DEGREES)
                return@repeat

            var appended = false
            for (next in best.outwardPoints.drop(1)) {
                val segment = distance(line.last(), next)
                if (segment < 0.03) continue
                val remaining = MAX_ADDED_METERS - addedMeters
                if (segment > remaining) {
                    if (remaining >= 1.0) {
                        val ratio = remaining / segment
                        line.add(GeoPointE6(
                            (line.last().latitudeE6 + (next.latitudeE6 - line.last().latitudeE6) *
                                ratio).roundToInt(),
                            (line.last().longitudeE6 + (next.longitudeE6 - line.last().longitudeE6) *
                                ratio).roundToInt()
                        ))
                        appended = true
                    }
                    addedMeters = MAX_ADDED_METERS
                    break
                }
                line.add(next)
                addedMeters += segment
                appended = true
            }
            if (!appended) return@repeat
            used.add(best.road)
            excluded.add(best.road)
            endingNodeId = best.endingNodeId
            if (addedMeters >= MAX_ADDED_METERS) return Extension(line, used)
        }
        return Extension(line, used)
    }
}
