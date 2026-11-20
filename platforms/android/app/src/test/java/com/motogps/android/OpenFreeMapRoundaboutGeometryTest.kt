package com.motogps.android

import com.motogps.android.jni.GeoPointE6
import com.motogps.android.map.MapRoadSegment
import com.motogps.android.map.OpenFreeMapRoundaboutGeometry
import com.motogps.android.map.RoadContextJunctions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class OpenFreeMapRoundaboutGeometryTest {
    private val lat = 45.0
    private val lon = 7.0
    private fun point(east: Double, north: Double): GeoPointE6 =
        GeoPointE6(((lat + north / 111320.0) * 1e6).roundToInt(),
            ((lon + east / (111320.0 * cos(Math.toRadians(lat)))) * 1e6).roundToInt())

    private fun road(vararg points: GeoPointE6, roadClass: Int = 2): MapRoadSegment =
        MapRoadSegment(roadClass, 50, points.minOf { it.latitudeE6 },
            points.minOf { it.longitudeE6 }, points.maxOf { it.latitudeE6 },
            points.maxOf { it.longitudeE6 }, points.toList())

    private fun octagonalRing(): Pair<List<MapRoadSegment>, List<MapRoadSegment>> {
        val vertices = (0..8).map { i ->
            val angle = 2 * PI * (i % 8) / 8
            point(18.0 * cos(angle), 18.0 * sin(angle))
        }
        // Each MVT way is only an arc: no way by itself is a closed loop.
        val ring = (0..7).map { i -> road(vertices[i], vertices[i + 1]) }
        val arms = listOf(0, 2, 4, 6).map { i ->
            val angle = 2 * PI * i / 8
            road(vertices[i], point(65.0 * cos(angle), 65.0 * sin(angle)))
        }
        return ring to arms
    }

    @Test fun ofmSegmentedRoundaboutGetsWholeRingAndDistinctBranches() {
        val (ring, arms) = octagonalRing()
        val analysis = OpenFreeMapRoundaboutGeometry.analyze(
            ring + arms, lat, lon, point(0.0, 80.0))
        val focus = analysis.focus
        assertNotNull(focus)
        assertEquals(8, focus!!.members.size)
        assertTrue(focus.exits.size >= 3)
        assertEquals(focus.ring.points.first(), focus.ring.points.last())
        assertTrue(focus.key.startsWith("ofm:"))
        assertTrue(focus.ring.nodeIds.isEmpty())
        assertEquals(null, focus.ring.osmWayId)
        assertFalse(focus.osmTagged)
    }

    @Test fun aCrossingWithNoSharedEndpointIsNotAnExit() {
        val (ring, arms) = octagonalRing()
        val crossing = road(point(-80.0, 0.0), point(80.0, 0.0))
        val focus = OpenFreeMapRoundaboutGeometry.analyze(
            ring + arms + crossing, lat, lon, null).focus
        assertNotNull(focus)
        assertFalse(focus!!.exits.contains(crossing))
    }

    @Test fun aSquareUrbanBlockMustNotBeTreatedAsRoundabout() {
        val v = listOf(point(-25.0, -25.0), point(25.0, -25.0),
            point(25.0, 25.0), point(-25.0, 25.0))
        val loop = (0..3).map { road(v[it], v[(it + 1) % 4]) }
        val arms = listOf(road(v[0], point(-75.0, -75.0)),
            road(v[1], point(75.0, -75.0)),
            road(v[2], point(75.0, 75.0)))
        assertNull(OpenFreeMapRoundaboutGeometry.analyze(
            loop + arms, lat, lon, null).focus)
    }

    @Test fun unconnectedArcsMustNotFormFalseExitsOrLoops() {
        val (ring, arms) = octagonalRing()
        val shortened = ring.drop(1) + arms
        assertNull(OpenFreeMapRoundaboutGeometry.analyze(
            shortened, lat, lon, null).focus)
    }

    @Test fun existingOsmTopologyRemainsSeparateFromOfmFallback() {
        val (ring, arms) = octagonalRing()
        val native = ring.first().copy(osmWayId = 123L, nodeIds = listOf(100L, 101L))
        val result = OpenFreeMapRoundaboutGeometry.analyze(
            listOf(native) + ring.drop(1) + arms, lat, lon, null)
        assertNull(result.focus)
        assertTrue(RoadContextJunctions.find(ring + arms, lat, lon, null) == null)
    }
}
