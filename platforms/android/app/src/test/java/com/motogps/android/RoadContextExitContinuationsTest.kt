package com.motogps.android

import com.motogps.android.jni.GeoPointE6
import com.motogps.android.map.MapRoadSegment
import com.motogps.android.map.RoadContextJunctions
import com.motogps.android.viewmodel.MapSceneRefreshPolicy
import com.motogps.android.viewmodel.RoadContextExitContinuations
import com.motogps.android.viewmodel.RoadContextSceneSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadContextExitContinuationsTest {
    private fun p(north: Int, east: Int = 0) =
        GeoPointE6(45_000_000 + north, 8_000_000 + east)
    private fun road(id: Long, points: List<GeoPointE6>, nodes: List<Long>) =
        MapRoadSegment(3, 50, 0, 0, 0, 0, points,
            osmWayId = id, nodeIds = nodes)

    private val ring = road(1,
        listOf(p(0), p(200, 200), p(400), p(200, -200), p(0)),
        listOf(10, 11, 12, 13, 10)).copy(junctionKind = 1)
    private val exit = road(2, listOf(p(0), p(-600)), listOf(10, 20))
    private val onward = road(3,
        listOf(p(-600), p(-1_600), p(-2_600)),
        listOf(20, 21, 22))

    private fun extend(
        seed: MapRoadSegment = exit,
        alternatives: List<MapRoadSegment> = listOf(onward)
    ) = RoadContextExitContinuations.extend(
        seed, listOf(ring, seed) + alternatives,
        ring.nodeIds.toSet(), setOf(ring), setOf(seed),
        emptySet(), 45.0)

    @Test fun linkedWayAddsLongerExitWithoutAnotherPolyline() {
        val ext = extend()
        assertEquals(listOf(onward), ext.consumedWays)
        assertEquals(p(0), ext.points.first())
        assertTrue(ext.points.contains(p(-1_600)))
        assertTrue(ext.points.contains(p(-2_600)))
    }

    @Test fun reversedExitAndReversedFollowingWayUseTrueNodeOrder() {
        val reverseSeed = exit.copy(
            points = exit.points.asReversed(),
            nodeIds = exit.nodeIds.asReversed())
        val reverseOnward = onward.copy(
            points = onward.points.asReversed(),
            nodeIds = onward.nodeIds.asReversed())
        val ext = extend(reverseSeed, listOf(reverseOnward))
        assertEquals(1, ext.consumedWays.size)
        assertEquals(p(0), ext.points.first())
        assertTrue(ext.points.contains(p(-2_600)))
    }

    @Test fun matchingCoordinateWithoutSharedNodeIdMustNotConnect() {
        val unrelated = road(4, listOf(p(-600), p(-1_600)), listOf(99, 100))
        val ext = extend(alternatives = listOf(unrelated))
        assertTrue(ext.consumedWays.isEmpty())
        assertEquals(exit.points, ext.points)
    }

    @Test fun ambiguousForkIsNotInventedAndSharpTurnIsRejected() {
        val fork = road(4, listOf(p(-600), p(-1_600, 100)), listOf(20, 40))
        val ambiguous = extend(alternatives = listOf(onward, fork))
        assertTrue(ambiguous.consumedWays.isEmpty())
        val sharp = road(5, listOf(p(-600), p(-600, 1_500)), listOf(20, 50))
        assertTrue(extend(alternatives = listOf(sharp)).consumedWays.isEmpty())
    }

    @Test fun extensionKeepsOnlyBoundedLengthAndDoesNotConsumeOtherExit() {
        val far = road(6, listOf(p(-600), p(-6_000)), listOf(20, 60))
        val ext = extend(alternatives = listOf(far))
        assertEquals(1, ext.consumedWays.size)
        assertTrue(ext.points.last().latitudeE6 > p(-4_000).latitudeE6)
        val blocked = RoadContextExitContinuations.extend(
            exit, listOf(exit, onward), ring.nodeIds.toSet(),
            emptySet(), setOf(exit, onward), emptySet(), 45.0)
        assertTrue(blocked.consumedWays.isEmpty())
    }

    @Test fun selectorMergesFollowingWayIntoExitWithoutSacrificingGpsReserve() {
        val focus = RoadContextJunctions.Priority(
            ring = ring, members = listOf(ring), exits = listOf(exit),
            junctionPoints = setOf(p(0)), confidence = 100,
            gpsDistanceM = 0.0, upcomingDistanceM = 0.0,
            key = "synthetic", rawExitWays = 1, distinctExitBranches = 1)
        val local = (0 until 24).map { i ->
            road(1_000L + i,
                listOf(p(-200 + i * 25, 250), p(-150 + i * 25, 330)),
                listOf(10_000L + i * 2, 10_001L + i * 2))
        }
        val result = RoadContextSceneSelector.select(
            listOf(ring, exit, onward) + local, focus, 45.0, 8.0, null)
        val displayExit = result.drawn.first { it.role == RoadContextSceneSelector.Role.EXIT }
        assertEquals(exit, displayExit.road)
        assertTrue(displayExit.points.contains(p(-1_600)))
        assertEquals(1, result.continuedExitWays)
        assertEquals(1, result.continuedExitBranches)
        assertFalse(result.drawn.any { it.road == onward })
        assertEquals(1, result.count(RoadContextSceneSelector.Role.RING))
        assertTrue(result.count(RoadContextSceneSelector.Role.GPS) >= 6)
        assertTrue(result.slots <= 20 && result.points <= 160)
    }

    @Test fun sceneRefreshUsesShorterCadenceOnlyNearKnownRoundabout() {
        assertEquals(MapSceneRefreshPolicy.Limits(2_500L, 18.0),
            MapSceneRefreshPolicy.limits("turn_right", 85, 2))
        assertEquals(MapSceneRefreshPolicy.Limits(3_500L, 30.0),
            MapSceneRefreshPolicy.limits("turn_right", 200, 2))
        assertEquals(MapSceneRefreshPolicy.Limits(3_500L, 30.0),
            MapSceneRefreshPolicy.limits("turn_left", 60, 0))
        assertEquals(MapSceneRefreshPolicy.Limits(6_000L, 60.0),
            MapSceneRefreshPolicy.limits("continue", 40, 3))
        assertEquals(MapSceneRefreshPolicy.Limits(6_000L, 60.0),
            MapSceneRefreshPolicy.limits(null, Long.MAX_VALUE, 0))
    }
}
