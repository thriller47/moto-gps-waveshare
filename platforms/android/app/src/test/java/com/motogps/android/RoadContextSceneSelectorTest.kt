package com.motogps.android

import com.motogps.android.jni.GeoPointE6
import com.motogps.android.map.MapRoadSegment
import com.motogps.android.map.RoadContextJunctions
import com.motogps.android.viewmodel.RoadContextSceneSelector
import com.motogps.android.viewmodel.RoadContextUpcoming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadContextSceneSelectorTest {
    private fun p(n: Int, e: Int) = GeoPointE6(45_000_000 + n, 8_000_000 + e)
    private fun road(id: Long, pts: List<GeoPointE6>, ids: List<Long>) =
        MapRoadSegment(3, 50, 0, 0, 0, 0, pts, osmWayId = id, nodeIds = ids)
    private val ring = road(1, listOf(p(0, 0), p(120, 120),
        p(240, 0), p(120, -120), p(0, 0)), listOf(100, 101, 102, 103, 100))
    private val exits = (0 until 5).map { i ->
        val join = when (i % 3) {
            0 -> p(120, 120)
            1 -> p(240, 0)
            else -> p(120, -120)
        }
        road(10 + i.toLong(), listOf(join,
            p(400 + i * 90, 350 + i * 100)),
            listOf(if (i % 3 == 0) 101 else if (i % 3 == 1) 102 else 103,
                800L + i))
    }
    private val local = (0 until 24).map { i ->
        val lat = (i - 12) * 20
        road(100 + i.toLong(),
            (0..12).map { j -> p(lat + j * 14, -240 + i * 16) },
            (0..12).map { j -> 5000L + i * 100 + j })
    }

    @Test fun denseSceneGuaranteesSixNonFocusGpsRoadsWithinTwentyAnd160() {
        val roads = listOf(ring) + exits + local
        val focus = RoadContextJunctions.find(roads, 45.0, 8.0, p(240, 0))
        assertTrue(focus != null)
        val result = RoadContextSceneSelector.select(roads, focus, 45.0, 8.0,
            p(240, 0), listOf(p(0, 0), p(1000, 0)))
        assertEquals(1, result.count(RoadContextSceneSelector.Role.RING))
        // Five OSM ways correspond to only three distinct outward branches.
        assertEquals(3, result.count(RoadContextSceneSelector.Role.EXIT))
        assertEquals(5, focus?.rawExitWays)
        assertEquals(3, focus?.distinctExitBranches)
        assertTrue(result.count(RoadContextSceneSelector.Role.GPS) >= 6)
        assertTrue(result.slots <= 20)
        assertTrue(result.points <= 160)
        assertEquals(result.slots, result.drawn.map { it.road }.distinct().size)
        assertEquals(result.drawn.first().points.first(), result.drawn.first().points.last())
        // All selected geometry comes from either one reconstructed ring or a way.
        assertFalse(result.drawn.any { it.points.size < 2 })
    }

    @Test fun offRouteGpsContextRemainsVisibleWithoutAnyRing() {
        val result = RoadContextSceneSelector.select(local, null, 45.0, 8.0,
            p(1200, 0), listOf(p(5000, 0), p(7000, 0)))
        assertTrue(result.count(RoadContextSceneSelector.Role.GPS) >= 6)
        assertTrue(result.offRouteGpsRoads >= 6)
        assertTrue(result.slots > 6)
    }

    @Test fun missingUpcomingDoesNotDropLocalRoads() {
        val result = RoadContextSceneSelector.select(local, null, 45.0, 8.0, null)
        assertEquals(0, result.count(RoadContextSceneSelector.Role.UPCOMING))
        assertTrue(result.count(RoadContextSceneSelector.Role.GPS) >= 6)
        assertTrue(result.slots <= 20 && result.points <= 160)
    }

    @Test fun overlappingFocusAndNearestAreNotDuplicated() {
        val roads = listOf(ring) + exits + local
        val focus = RoadContextJunctions.find(roads, 45.0, 8.0, null)
        val result = RoadContextSceneSelector.select(roads, focus, 45.0, 8.0, p(0, 0))
        assertEquals(result.drawn.map { it.road }.distinct().size, result.slots)
        assertEquals(1, result.count(RoadContextSceneSelector.Role.RING))
    }

    @Test fun upcomingInterpolationIsSpeedDependentAndWorksWithMissingFixAccuracy() {
        val route = listOf(p(0, 0), p(3000, 0))
        val slow = RoadContextUpcoming.ahead(route, 45.0, 0f, Float.POSITIVE_INFINITY)
        val fast = RoadContextUpcoming.ahead(route, 45.0, 20f, 3f)
        assertTrue(fast!!.latitudeE6 > slow!!.latitudeE6)
        assertEquals(null, RoadContextUpcoming.ahead(emptyList(), 45.0, 5f, 5f))
        assertEquals(route.first(), RoadContextUpcoming.ahead(route.take(1), 45.0, 5f, 5f))
    }
}
