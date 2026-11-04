package com.motogps.android

import com.motogps.android.jni.GeoPointE6
import com.motogps.android.map.MapRoadSegment
import com.motogps.android.map.RoadContextTopology
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadContextTopologyTest {
    private val lat = 45_000_000
    private val lon = 8_000_000
    private fun pt(north: Int, east: Int) = GeoPointE6(lat + north, lon + east)
    private val route = listOf(pt(-1_000, 0), pt(1_000, 0))

    private fun road(
        id: Long,
        level: Int,
        nodes: List<Long>,
        points: List<GeoPointE6>
    ) = MapRoadSegment(
        roadClass = 3, maxSpeedKph = 50, minLatE6 = 0, minLonE6 = 0,
        maxLatE6 = 0, maxLonE6 = 0, points = points,
        osmWayId = id, layer = level, nodeIds = nodes
    )

    private fun filter(vararg roads: MapRoadSegment, path: List<GeoPointE6> = route) =
        RoadContextTopology.excludeDisconnectedCrossings(roads.toList(), path, 45.0, 8.0)

    @Test fun bridgeAndMotorwayWithIndependentOsmNodesAreNotAnIntersection() {
        val bridge = road(1, 1, listOf(10, 11, 12),
            listOf(pt(-500, 0), pt(0, 0), pt(500, 0)))
        val motorway = road(2, 0, listOf(20, 21),
            listOf(pt(0, -600), pt(0, 600)))
        assertEquals(listOf(bridge), filter(bridge, motorway))
    }

    @Test fun actualJunctionIsKeptBecauseItsOsmNodeIsShared() {
        val main = road(1, 0, listOf(10, 11, 12),
            listOf(pt(-500, 0), pt(0, 0), pt(500, 0)))
        val connecting = road(2, 0, listOf(21, 11, 22),
            listOf(pt(0, -600), pt(0, 0), pt(0, 600)))
        assertEquals(listOf(main, connecting), filter(main, connecting))
    }

    @Test fun sameLevelVisualCrossingWithoutJunctionIsExcluded() {
        val main = road(1, 0, listOf(10, 11),
            listOf(pt(-500, 0), pt(500, 0)))
        val notConnected = road(2, 0, listOf(20, 21),
            listOf(pt(0, -600), pt(0, 600)))
        assertEquals(listOf(main), filter(main, notConnected))
    }

    @Test fun underpassIsExcludedWhenRiderStaysOnGroundLevel() {
        val surfaceRoad = road(1, 0, listOf(10, 11),
            listOf(pt(-500, 0), pt(500, 0)))
        val overpass = road(2, 1, listOf(20, 21),
            listOf(pt(0, -600), pt(0, 600)))
        assertEquals(listOf(surfaceRoad), filter(surfaceRoad, overpass))
    }

    @Test fun roundaboutExitWithSharedNodeAndParallelRoadRemainVisible() {
        val main = road(1, 0, listOf(10, 11, 12),
            listOf(pt(-500, 0), pt(0, 0), pt(500, 0)))
        val exit = road(2, 0, listOf(11, 21, 22),
            listOf(pt(0, 0), pt(50, 200), pt(350, 450)))
        val parallel = road(3, 0, listOf(30, 31),
            listOf(pt(-500, 500), pt(500, 500)))
        assertEquals(listOf(main, exit, parallel), filter(main, exit, parallel))
    }

    @Test fun missingMetadataFailsOpenAndNeverRemovesLegacyRoads() {
        val main = road(1, 1, listOf(10, 11), listOf(pt(-500, 0), pt(500, 0)))
        val legacy = road(2, 0, emptyList(), listOf(pt(0, -600), pt(0, 600)))
        assertEquals(listOf(main, legacy), filter(main, legacy))
    }

    @Test fun uncertainRouteMatchDisablesFiltering() {
        val main = road(1, 1, listOf(10, 11), listOf(pt(-500, 0), pt(500, 0)))
        val motorway = road(2, 0, listOf(20, 21), listOf(pt(0, -600), pt(0, 600)))
        val distant = listOf(pt(-1_000, 10_000), pt(1_000, 10_000))
        assertEquals(listOf(main, motorway), filter(main, motorway, path = distant))
    }

    @Test fun noRoadContextOrRouteNeverCrashes() {
        assertTrue(filter().isEmpty())
        val main = road(1, 0, listOf(1, 2), listOf(pt(-500, 0), pt(500, 0)))
        assertEquals(listOf(main), filter(main, path = emptyList()))
    }
}
