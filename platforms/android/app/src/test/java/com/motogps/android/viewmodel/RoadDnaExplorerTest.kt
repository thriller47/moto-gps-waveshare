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
