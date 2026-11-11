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

    @Test fun multiContactWayUsesGenuineOutgoingAttachmentNotNearestRingNode() {
        // Regression for the 2026-11-11 double-roundabout trace: a way can
        // follow a ring edge before leaving it. The nearest shared node is
        // NOT necessarily an exit. No fake branch may consume a slot.
        val followsRingThenLeaves = road(91,
            listOf(p(0, 0), p(120, 120), p(120, 550)),
            listOf(100, 101, 990))
        val result = RoadContextJunctions.find(
            listOf(ring, followsRingThenLeaves, exitB), 45.0, 8.0,
            p(0, 0))
        assertNotNull(result)
        assertTrue(result!!.exits.contains(followsRingThenLeaves))
        assertTrue(result.junctionPoints.contains(p(120, 120)))
        assertFalse("Internal attachment must not be labelled an exit",
            result.junctionPoints.contains(p(0, 0)))
        assertEquals(2, result.distinctExitBranches)
    }

    @Test fun ringToRingConnectorWithoutOutsideNeighbourIsNotAnExit() {
        val internal = road(92, listOf(p(0, 0), p(120, 120)),
            listOf(100, 101))
        val result = RoadContextJunctions.find(
            listOf(ring, internal, exitA, exitB), 45.0, 8.0, p(0, 0))
        assertNotNull(result)
        assertEquals(setOf(2L, 3L),
            result!!.exits.mapNotNull { it.osmWayId }.toSet())
        assertEquals(2, result.rawExitWays)
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

    @Test fun connectedRingOnForwardRouteIsSelectedBeforeItsGpsProximityWindow() {
        val offset = 3900
        val aheadRing = road(150, ringPoints.map {
            p(it.latitudeE6 - 45_000_000 + offset,
                it.longitudeE6 - 8_000_000)
        }, ring.nodeIds)
        val ringExitA = road(151,
            listOf(p(120 + offset, 120), p(120 + offset, 550)),
            listOf(101, 500))
        val ringExitB = road(152,
            listOf(p(240 + offset, 0), p(700 + offset, 0)),
            listOf(102, 600))
        val data = listOf(aheadRing, ringExitA, ringExitB)
        assertNull(RoadContextJunctions.find(data, 45.0, 8.0, p(900, 0)))
        val selected = RoadContextJunctions.find(
            data, 45.0, 8.0, p(900, 0),
            route = listOf(p(0, 0), p(offset, 0),
                p(offset + 120, 120), p(offset + 240, 0), p(6000, 0)))
        assertNotNull(selected)
        assertEquals(2, selected?.exits?.size)
        assertTrue((selected?.forwardRouteAlongM ?: 0.0) > 350.0)
    }

    @Test fun ringAtTheNextTurnOutranksAnEquallyCredibleRingFartherAlongTheRoute() {
        // Regression for the 2026-11-10 07:47 stop: the local multi-way
        // roundabout lost its forward bonus below 35m; a second circle
        // approximately 190m ahead took all ring/exit slots instead.
        val offset = 1700
        val remoteRing = road(160, ringPoints.map {
            p(it.latitudeE6 - 45_000_000 + offset,
                it.longitudeE6 - 8_000_000)
        }, listOf(810, 811, 812, 813, 810))
        val remoteA = road(161,
            listOf(p(offset + 120, 120), p(offset + 120, 550)),
            listOf(811, 820))
        val remoteB = road(162,
            listOf(p(offset + 240, 0), p(offset + 700, 0)),
            listOf(812, 821))
        val roads = listOf(ring, exitA, exitB, remoteRing, remoteA, remoteB)
        val nearRoute = listOf(p(-80, 0), p(0, 0), p(2400, 0))
        // The farther ring is exactly on the upcoming probe. Its score
        // matches the local ring's, but the local next maneuver must win.
        val analysis = RoadContextJunctions.analyze(roads,
            44.99992, 8.0, p(offset + 120, 0), nearRoute)
        assertTrue(analysis.qualified >= 2)
        assertEquals(ring, analysis.focus?.ring)
        assertTrue(analysis.focus!!.gpsDistanceM < 35.0)
        assertTrue(analysis.focus!!.forwardRouteAlongM < 35.0)
        assertEquals(setOf(2L, 3L),
            analysis.focus!!.exits.mapNotNull { it.osmWayId }.toSet())
    }


    @Test fun bypassElevenMetresAwayDoesNotEarnOnRouteRingBonus() {
        val bypass = listOf(p(340, -450), p(340, 450))
        val focus = RoadContextJunctions.find(
            multipleWays() + exitA + exitB, 45.00034, 8.0, p(340, 450), bypass)!!
        assertEquals(4, focus.members.size)
        assertTrue(focus.forwardRouteDistanceM in 8.0..20.0)
        assertEquals(0.0, focus.forwardRouteOverlapM, 0.01)
        assertEquals(setOf(2L, 3L),
            focus.exits.mapNotNull { it.osmWayId }.toSet())
        assertTrue(focus.confidence < 120)
    }

    @Test fun outerLaneCurveOfWideRoundaboutEarnsRingBonus() {
        // A wider two-lane roundabout: the route follows a parallel outer
        // sweep, up to ~12m away from the OSM ring's centreline. Its shape
        // bends around the ring over two connected arcs.
        val outerLane = listOf(
            p(-140, -10), p(120, 270), p(380, -10)
        )
        val focus = RoadContextJunctions.find(
            multipleWays() + exitA + exitB,
            45.00012, 8.00024, p(120, 270), outerLane)!!
        assertTrue("Outer curved lane must be associated with ring",
            focus.forwardRouteOverlapM >= 12.0)
        assertTrue("Outer lane must earn forward ring score", focus.confidence >= 120)
        assertEquals(2, focus.exits.size)
    }

    @Test fun curvedBypassOutsideRingMustNotEarnBonusWithoutTwoArcTracking() {
        // A bend only tangent to a single side of a ring is not a roundabout
        // traversal. The generous outer-lane allowance must not turn this
        // into an on-route ring.
        val bypass = listOf(p(450, -600), p(440, 0), p(450, 600))
        val focus = RoadContextJunctions.find(
            multipleWays() + exitA + exitB,
            45.00023, 8.00043, p(440, 0), bypass)!!
        assertTrue(focus.forwardRouteOverlapM < 12.0)
        assertTrue(focus.confidence < 120)
    }

    @Test fun routeFollowingRingArcEarnsOnRouteBonus() {
        val actualArc = listOf(p(-100, 0), p(0, 0), p(120, 120),
            p(240, 0), p(450, 0))
        val focus = RoadContextJunctions.find(
            multipleWays() + exitA + exitB, 45.0, 8.0,
            p(120, 120), actualArc)!!
        assertTrue(focus.forwardRouteOverlapM >= 12.0)
        assertTrue(focus.confidence >= 120)
    }

    @Test fun nearbyBypassRingStaysVisibleBeforeSecondTrueRing() {
        val offset = 1750 // ~195m separation
        val farRing = road(200, ringPoints.map {
            p(it.latitudeE6 - 45_000_000 + offset,
              it.longitudeE6 - 8_000_000)
        }, listOf(800, 801, 802, 803, 800))
        val farA = road(201,
            listOf(p(offset + 120, 120), p(offset + 120, 550)),
            listOf(801, 901))
        val farB = road(202,
            listOf(p(offset + 240, 0), p(offset + 700, 0)),
            listOf(802, 902))
        val route = listOf(p(340, -450), p(340, 0), p(340, 450),
            p(offset, 0), p(offset + 120, 120),
            p(offset + 240, 0), p(offset + 500, 0))
        val focus = RoadContextJunctions.find(
            multipleWays() + exitA + exitB + farRing + farA + farB,
            45.00034, 8.0, p(offset + 120, 120), route)!!
        assertEquals(4, focus.members.size)
        assertTrue(focus.gpsDistanceM < 45.0)
        assertEquals(0.0, focus.forwardRouteOverlapM, 0.01)
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

    @Test fun eightConnectedBranchesAreDistributedAroundRingWithinSevenSlots() {
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
        assertEquals(7, selection?.exits?.size)
        assertEquals(7, selection?.junctionPoints?.size)
        // Geographic coordinates are not a metric circle: the first radial
        // OSM way is not guaranteed to be the closest to the GPS.
        val closest = approaches.minByOrNull { way ->
            val attach = way.points.first()
            val dy = (attach.latitudeE6 - 45_000_000) / 1e6 * 111_320.0
            val dx = (attach.longitudeE6 - 8_000_000) / 1e6 * 111_320.0 *
                kotlin.math.cos(Math.toRadians(45.0))
            dx * dx + dy * dy
        }
        assertEquals(closest, selection?.exits?.first())
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
