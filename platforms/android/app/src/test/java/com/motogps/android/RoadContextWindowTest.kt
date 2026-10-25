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
}
