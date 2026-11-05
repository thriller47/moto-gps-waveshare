package com.motogps.android

import com.motogps.android.jni.GeoPointE6
import com.motogps.android.map.MapRoadSegment
import com.motogps.android.map.RoadContextJunctions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadContextJunctionsTest {
    private fun p(n: Int, e: Int) = GeoPointE6(45_000_000 + n, 8_000_000 + e)
    private fun road(id: Long, pts: List<GeoPointE6>, ids: List<Long>, layer: Int = 0) =
        MapRoadSegment(3, 50, 0, 0, 0, 0, pts, osmWayId = id, layer = layer, nodeIds = ids)

    private val ringPoints = listOf(
        p(0, 0), p(120, 120), p(240, 0), p(120, -120), p(0, 0))
    private val ring = road(1, ringPoints, listOf(100, 101, 102, 103, 100))
    private val exitA = road(2, listOf(p(120, 120), p(120, 550)), listOf(101, 200))
    private val exitB = road(3, listOf(p(240, 0), p(700, 0)), listOf(102, 300))

    private fun multipleWays(): List<MapRoadSegment> = listOf(
        road(11, listOf(p(0, 0), p(120, 120)), listOf(100, 101)),
        road(12, listOf(p(120, 120), p(240, 0)), listOf(101, 102)),
        road(13, listOf(p(240, 0), p(120, -120)), listOf(102, 103)),
        road(14, listOf(p(120, -120), p(0, 0)), listOf(103, 100))
    )

    @Test fun singleWayRingIsSelectedWithRealExits() {
        val unrelated = road(4, listOf(p(-50, -60), p(-50, 400)), listOf(400, 401))
        val analysis = RoadContextJunctions.analyze(
            listOf(unrelated, exitA, exitB, ring), 44.9988, 8.0, p(0, 0))
        assertEquals(ring, analysis.focus?.ring)
        assertEquals(setOf(2L, 3L),
            analysis.focus?.exits?.mapNotNull { it.osmWayId }?.toSet())
        assertTrue(analysis.candidates >= 1)
        assertTrue((analysis.focus?.confidence ?: 0) >= 70)
        assertEquals(1, analysis.focus?.members?.size)
    }

    @Test fun fourOsmWaysAreCombinedIntoOneClosedRingWithConnectedExits() {
        val analysis = RoadContextJunctions.analyze(
            multipleWays() + exitA + exitB, 44.9991, 8.0, p(120, 0))
        val focus = analysis.focus
        assertNotNull(focus)
        assertEquals(4, focus?.members?.size)
        assertEquals(focus?.ring?.points?.first(), focus?.ring?.points?.last())
        assertEquals(setOf(2L, 3L), focus?.exits?.mapNotNull { it.osmWayId }?.toSet())
        assertTrue(focus?.junctionPoints?.contains(p(120, 120)) == true)
        assertTrue(focus?.junctionPoints?.contains(p(240, 0)) == true)
        assertTrue(analysis.qualified >= 1)
    }

    @Test fun incompleteExitNodesAreNotInventedByVisualCrossings() {
        val unverified = exitB.copy(nodeIds = emptyList())
        val focus = RoadContextJunctions.find(
            listOf(ring, exitA, unverified), 45.0, 8.0, null)
        assertEquals(listOf(exitA), focus?.exits)
    }

    @Test fun aFourWayJunctionIsNotARing() {
        val north = road(10, listOf(p(-500, 0), p(0, 0)), listOf(1, 2))
        val south = road(11, listOf(p(0, 0), p(500, 0)), listOf(2, 3))
        val west = road(12, listOf(p(0, -500), p(0, 0)), listOf(4, 2))
        val east = road(13, listOf(p(0, 0), p(0, 500)), listOf(2, 5))
        assertNull(RoadContextJunctions.find(
            listOf(north, south, west, east), 45.0, 8.0, null))
    }

    @Test fun aCityBlockWithNoExitsFailsConfidenceThreshold() {
        val analysis = RoadContextJunctions.analyze(
            multipleWays(), 45.0, 8.0, p(0, 0))
        assertNull(analysis.focus)
        assertTrue(analysis.candidates > 0)
        assertTrue((analysis.excluded["confidence"] ?: 0) > 0)
    }

    @Test fun impreciseOsmClosureCannotTeleportAcrossTheMap() {
        val bad = ring.copy(points = ringPoints.dropLast(1) + p(500, 500))
        assertFalse(RoadContextJunctions.isClosedRing(bad))
        assertNull(RoadContextJunctions.find(listOf(bad, exitA, exitB),
            45.0, 8.0, null))
    }

    @Test fun oneConnectedExitCanBeRecognizedWhenConfidenceSuffices() {
        val focus = RoadContextJunctions.find(
            listOf(ring, exitA), 45.0, 8.0, null)
        assertEquals(1, focus?.exits?.size)
    }

    @Test fun gradeSeparationMetadataIsNotUsedToFilterTheDisplay() {
        val focus = RoadContextJunctions.find(
            listOf(ring.copy(layer = 1), exitA.copy(layer = -1),
                exitB.copy(layer = 0)), 45.0, 8.0, null)
        assertEquals(2, focus?.exits?.size)
    }

    @Test fun aDistantRingIsNotChosenAndOrdinaryRoadsRemainAvailable() {
        assertNull(RoadContextJunctions.find(
            listOf(ring, exitA, exitB), 45.005, 8.005, null))
    }

    @Test fun unrelatedStreetsWithoutAClosedCircuitRemainInFallback() {
        val side = road(8, listOf(p(0, 0), p(300, 0)), listOf(11, 12))
        val crossing = road(9, listOf(p(0, 0), p(0, 200)), listOf(11, 13))
        assertNull(RoadContextJunctions.find(listOf(side, crossing), 45.0, 8.0, null))
    }

    @Test fun duplicateWaysAtSameJunctionAndBearingOccupyOnlyOneExitSlot() {
        val sameDirection = road(51,
            listOf(p(120, 120), p(150, 600)), listOf(101, 700))
        val focus = RoadContextJunctions.find(
            listOf(ring, exitA, sameDirection, exitB), 45.0, 8.0, null)
        assertEquals(3, focus?.rawExitWays)
        assertEquals(2, focus?.distinctExitBranches)
        assertEquals(2, focus?.exits?.size)
        assertEquals(2, focus?.junctionPoints?.size)
    }

    @Test fun eightConnectedBranchesAreDistributedAroundRingWithinFiveSlots() {
        val octagon = (0 until 8).map { i ->
            val angle = i * Math.PI * 2.0 / 8.0
            p((170 * kotlin.math.sin(angle)).toInt(),
              (240 * kotlin.math.cos(angle)).toInt())
        }
        val ids = (100L..107L).toList()
        val circle = road(100, octagon + octagon.first(), ids + ids.first())
        val approaches = octagon.mapIndexed { index, near ->
            val angle = index * Math.PI * 2.0 / 8.0
            val far = p((750 * kotlin.math.sin(angle)).toInt(),
                        (900 * kotlin.math.cos(angle)).toInt())
            road(200 + index.toLong(), listOf(near, far),
                listOf(ids[index], 2000L + index))
        }
        val selection = RoadContextJunctions.find(
            listOf(circle) + approaches, 45.0, 8.0, p(0, 0))
        assertEquals(8, selection?.rawExitWays)
        assertEquals(8, selection?.distinctExitBranches)
        assertEquals(5, selection?.exits?.size)
        assertEquals(5, selection?.junctionPoints?.size)
        assertTrue(selection?.exits?.first() == approaches.first())
        assertTrue(selection!!.exits.any { it in approaches.subList(3, 6) })
    }

    @Test fun differentBearingsAtOneNodeStaySeparateBranches() {
        val east = road(61, listOf(p(0, 0), p(0, 700)), listOf(100, 601))
        val west = road(62, listOf(p(0, 0), p(0, -700)), listOf(100, 602))
        val selection = RoadContextJunctions.find(
            listOf(ring, east, west, exitB), 45.0, 8.0, null)
        assertEquals(3, selection?.distinctExitBranches)
        assertEquals(3, selection?.exits?.size)
    }
}
