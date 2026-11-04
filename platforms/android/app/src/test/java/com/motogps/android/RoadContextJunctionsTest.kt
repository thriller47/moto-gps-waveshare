package com.motogps.android

import com.motogps.android.jni.GeoPointE6
import com.motogps.android.map.MapRoadSegment
import com.motogps.android.map.RoadContextJunctions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadContextJunctionsTest {
    private fun p(n: Int, e: Int) = GeoPointE6(45_000_000 + n, 8_000_000 + e)
    private fun road(id: Long, points: List<GeoPointE6>, nodes: List<Long>) =
        MapRoadSegment(3, 50, 0, 0, 0, 0, points,
            osmWayId = id, layer = 0, nodeIds = nodes)

    private val circle = road(1, listOf(
        p(0, 0), p(120, 120), p(240, 0), p(120, -120), p(0, 0)
    ), listOf(100, 101, 102, 103, 100))

    @Test fun closedRingAndEveryConnectedExitAreIdentifiedBeforeEntering() {
        val exitA = road(2, listOf(p(120, 120), p(120, 550)), listOf(101, 200))
        val exitB = road(3, listOf(p(240, 0), p(700, 0)), listOf(102, 300))
        val unrelated = road(4, listOf(p(-50, -60), p(-50, 400)), listOf(400, 401))
        val focus = RoadContextJunctions.find(
            listOf(unrelated, exitA, exitB, circle), 44.9988, 8.0, p(0, 0))
        assertEquals(circle, focus?.ring)
        assertEquals(setOf(2L, 3L), focus?.exits?.mapNotNull { it.osmWayId }?.toSet())
    }

    @Test fun theAbsenceOfRingDoesNotRemoveAnyLocalRoads() {
        val side = road(8, listOf(p(0, 0), p(300, 0)), listOf(11, 12))
        val exit = road(9, listOf(p(0, 0), p(0, 200)), listOf(11, 13))
        assertNull(RoadContextJunctions.find(listOf(side, exit), 45.0, 8.0, null))
    }

    @Test fun ordinaryBendIsNotARoundabout() {
        val bend = road(5, listOf(p(0, 0), p(100, 100), p(200, 100), p(200, 0)),
            listOf(1, 2, 3, 4))
        assertFalse(RoadContextJunctions.isClosedRing(bend))
    }

    @Test fun ringMustBeCloseToRiderOrUpcomingRoad() {
        assertTrue(RoadContextJunctions.find(listOf(circle), 45.005, 8.005, null) == null)
    }
}
