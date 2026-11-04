package com.motogps.android

import com.motogps.android.jni.GeoPointE6
import com.motogps.android.map.MapRoadSegment
import com.motogps.android.map.RoadContextSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadContextSelectionTest {
    private fun p(lat: Int, lon: Int) = GeoPointE6(lat, lon)
    private fun r(id: Long, pts: List<GeoPointE6>, ids: List<Long>) = MapRoadSegment(
        roadClass = 3, maxSpeedKph = 50,
        minLatE6 = pts.minOf { it.latitudeE6 }, minLonE6 = pts.minOf { it.longitudeE6 },
        maxLatE6 = pts.maxOf { it.latitudeE6 }, maxLonE6 = pts.maxOf { it.longitudeE6 },
        points = pts, osmWayId = id, nodeIds = ids)

    @Test fun connectedExitBeatsUnrelatedNearbyStreet() {
        val route = listOf(p(45_000_000, 8_000_000), p(45_001_000, 8_000_000))
        val main = r(1, listOf(p(45_000_000, 8_000_000), p(45_000_500, 8_000_000),
            p(45_001_000, 8_000_000)), listOf(10, 11, 12))
        val exit = r(2, listOf(p(45_000_500, 8_000_000), p(45_000_500, 8_000_800)),
            listOf(11, 20))
        val side = r(3, listOf(p(45_000_010, 8_000_300), p(45_000_150, 8_000_300)),
            listOf(30, 31))
        val ordered = RoadContextSelection.rank(listOf(side, exit, main), route, 45.0, 8.0)
        assertEquals(1L, ordered.first().osmWayId)
        assertEquals(2L, ordered[1].osmWayId)
        assertTrue(ordered.contains(side))
    }

    @Test fun previousWayBreaksTie() {
        val route = listOf(p(45_000_000, 8_000_000), p(45_001_000, 8_000_000))
        val pts = listOf(p(45_000_100, 8_000_500), p(45_000_200, 8_000_500))
        val result = RoadContextSelection.rank(
            listOf(r(1, pts, listOf(1, 2)), r(2, pts, listOf(3, 4))),
            route, 45.0, 8.0, setOf(2L))
        assertEquals(2L, result.first().osmWayId)
    }

    @Test fun absentRouteFallsBackToNearestRoad() {
        val near = r(1, listOf(p(45_000_000, 8_000_000), p(45_000_100, 8_000_000)), listOf(1, 2))
        val far = r(2, listOf(p(45_001_000, 8_002_000), p(45_001_100, 8_002_000)), listOf(3, 4))
        assertEquals(1L, RoadContextSelection.rank(listOf(far, near), emptyList(), 45.0, 8.0).first().osmWayId)
    }
}
