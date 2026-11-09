package com.motogps.android

import com.motogps.android.jni.GeoPointE6
import com.motogps.android.viewmodel.RoadContextWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadContextWindowTest {
    @Test
    fun roadWindowUsesRiderLocationNotBeginningOfLongWay() {
        val road = (0..300).map { GeoPointE6(45_000_000 + it * 90, 8_000_000) }
        val result = RoadContextWindow.around(road, 45.0135, 8.0, 12)
        assertTrue(result.size in 2..12)
        assertTrue(result.first().latitudeE6 > 45_008_000)
        assertTrue(result.last().latitudeE6 > 45_013_000)
        assertTrue(result.first().latitudeE6 < 45_013_500)
    }

    @Test
    fun preserveSharpCornerAndBothDirectionsWithBudget() {
        val north = (0..100).map { GeoPointE6(45_000_000 + it * 18, 8_000_000) }
        val east = (1..100).map { GeoPointE6(45_001_800, 8_000_000 + it * 25) }
        val result = RoadContextWindow.around(north + east, 45.0018, 8.0, 8)
        assertTrue(result.size in 3..8)
        assertTrue(result.any { it.latitudeE6 == 45_001_800 && it.longitudeE6 == 8_000_000 })
        assertTrue(result.first().latitudeE6 < 45_001_800)
        assertTrue(result.last().longitudeE6 > 8_000_000)
    }

    @Test
    fun shortWindowIsAppliedEvenWhenWholeWayFitsPointBudget() {
        val road = (0..100).map { GeoPointE6(45_000_000 + it * 90, 8_000_000) }
        val result = RoadContextWindow.around(road, 45.0045, 8.0, 128)
        assertTrue(result.size < road.size)
        assertTrue(result.first().latitudeE6 > 45_001_000)
        assertTrue(result.last().latitudeE6 < 45_008_000)
    }

    @Test
    fun tinyRoadIsPreserved() {
        val points = listOf(GeoPointE6(45_000_000, 8_000_000), GeoPointE6(45_000_050, 8_000_000))
        assertEquals(points, RoadContextWindow.around(points, 45.0, 8.0, 12))
    }
    @Test
    fun closedRingIsFullyPreservedWhenMoreVerticesThanBudget() {
        // 20 regularly spaced points on a loop: the output must return to
        // its start and contain coordinates on all four sides.
        val points = (0 until 20).map { index ->
            val angle = index * (Math.PI * 2 / 20)
            GeoPointE6(45_000_000 + (120 * kotlin.math.sin(angle)).toInt(),
                8_000_000 + (170 * kotlin.math.cos(angle)).toInt())
        }
        val loop = points + points.first()
        val result = RoadContextWindow.closedRing(loop, 45.0, 12)
        assertEquals(12, result.size)
        assertEquals(result.first(), result.last())
        assertTrue(result.any { it.latitudeE6 > 45_000_090 })
        assertTrue(result.any { it.latitudeE6 < 44_999_910 })
        assertTrue(result.any { it.longitudeE6 > 8_000_125 })
        assertTrue(result.any { it.longitudeE6 < 7_999_875 })
    }


    @Test
    fun smallRingBudgetStillKeepsClosureAndAnExitNode() {
        val points = (0 until 24).map { index ->
            val angle = index * Math.PI * 2 / 24.0
            GeoPointE6(45_000_000 + (170 * kotlin.math.sin(angle)).toInt(),
                8_000_000 + (240 * kotlin.math.cos(angle)).toInt())
        }
        val ring = points + points.first()
        val exitNode = points[5]
        val output = RoadContextWindow.closedRing(ring, 45.0, 6, setOf(exitNode))
        assertEquals(6, output.size)
        assertEquals(output.first(), output.last())
        assertTrue(exitNode in output)
    }

    @Test
    fun approximateClosureIsNormalisedButOpenRoadIsRejected() {
        val pts = listOf(
            GeoPointE6(45_000_000, 8_000_000),
            GeoPointE6(45_000_200, 8_000_000),
            GeoPointE6(45_000_200, 8_000_200),
            GeoPointE6(45_000_000, 8_000_200),
            GeoPointE6(45_000_001, 8_000_000)
        )
        val output = RoadContextWindow.closedRing(pts, 45.0, 8)
        assertEquals(pts.first(), output.last())
        assertTrue(RoadContextWindow.closedRing(
            pts.dropLast(1) + GeoPointE6(45_000_500, 8_000_500), 45.0, 8).isEmpty())
    }
}
