package com.motogps.android.viewmodel

import org.junit.Assert.*
import org.junit.Test

class RoadDnaExplorerTest {
    @Test fun candidateBudgetAndMandatoryOrder() {
        val a = PlaceSearchResult("A", "", "", 45.1, 9.0)
        val b = PlaceSearchResult("B", "", "", 45.2, 9.1)
        val many = RoadDnaExplorer.stopPlans(45.0, 9.0, 150, 0, emptyList())
        assertTrue(many.size in 2..RoadDnaExplorer.MAX_ATTEMPTS)
        assertTrue(many.all { it.size == 3 })
        val single = RoadDnaExplorer.stopPlans(45.0, 9.0, 150, 0, listOf(a))
        assertTrue(single.all { it.first() == a })
        assertEquals(listOf(listOf(a, b)),
            RoadDnaExplorer.stopPlans(45.0, 9.0, 150, 0, listOf(a, b)))
    }

    @Test fun roadTagsAreNeutralWhenMissingAndBadSurfaceIsNotRewarded() {
        val p = ExplorerProfile.TOURING
        fun score(surface: String? = null, smoothness: String? = null,
                  width: String? = null, lanes: String? = null) =
            ExplorerRoadQuality.adjustment(p,surface,smoothness,width,lanes,null)
        assertEquals(0.0,score()!!,0.0001)
        assertEquals(0.0,score("unknown","n","variable","2;3")!!,0.0001)
        assertTrue(score("asphalt","good")!! > score()!!)
        assertTrue(score("asphalt","bad")!! < score()!!)
        assertTrue(score(width="2.1 m",lanes="1")!! < score()!!)
        assertNull(score(smoothness="impassable"))
        assertTrue(ExplorerRoadQuality.adjustment(ExplorerProfile.PANORAMA,null,null,null,null,"yes")!! < 0)
    }

    @Test fun anchorsMustBeReachedInOrder() {
        val a = PlaceSearchResult("A","","DNA stradale",45.0,9.0)
        val b = PlaceSearchResult("B","","DNA stradale",45.02,9.0)
        val shape = listOf(45.0 to 9.0,45.01 to 9.0,45.02 to 9.0)
        assertTrue(RoadDnaRoadIndex.anchorsReached(listOf(a,b),shape))
        assertFalse(RoadDnaRoadIndex.anchorsReached(listOf(b,a),shape))
    }

    @Test fun junctionExclusionDoesNotCountRightAngleAsRoadCurve() {
        val shape = listOf(45.0 to 9.0,45.003 to 9.0,45.003 to 9.005)
        val plain = RoadDnaTerrainAnalysis.curveMetrics(shape)
        assertTrue(plain.total > 0)
        val d = RoadDnaTerrainAnalysis.distance(shape[0],shape[1])
        val filtered = RoadDnaTerrainAnalysis.curveMetrics(shape,excluded=listOf((d-100)..(d+100)))
        assertEquals(0,filtered.total)
    }

    @Test fun retracingAndDuplicateGeometry() {
        val origin = 45.0 to 9.0
        val shape = listOf(origin, 45.01 to 9.0, 45.02 to 9.0)
        val outward = RoadDnaExplorer.footprint(shape, origin)
        val returning = RoadDnaExplorer.footprint(shape + shape.reversed(), origin)
        assertEquals(0.0, outward.repeated, 0.001)
        assertTrue(returning.repeated > 0.35)
        assertEquals(1.0, RoadDnaExplorer.similarity(outward, returning), 0.001)
        assertEquals(1.0, RoadDnaExplorer.similarity(outward, outward), 0.001)
    }
}
