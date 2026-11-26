package com.motogps.android.viewmodel

import org.junit.Assert.*
import org.junit.Test

class SpeedPackRouteMatcherTest {
    private val a = SpeedPackRouteMatcher.Point(45.0,7.0)
    private val b = SpeedPackRouteMatcher.Point(45.0,7.002)
    private fun evidence(lat: Double=45.0, bearing: Double=90.0, oneway: String?=null,
                         direction: String?=null) =
        SpeedPackRouteMatcher.Evidence("osm","1",10,
            SpeedPackRouteMatcher.Point(lat,7.001),bearing,oneway,direction)
    private fun match(e: SpeedPackRouteMatcher.Evidence, route: List<SpeedPackRouteMatcher.Point> = listOf(a,b)) =
        SpeedPackRouteMatcher.match(route,listOf(e))

    @Test fun straightAndUnknownDirection() {
        val matches = match(evidence())
        assertEquals(1,matches.size)
        assertTrue(matches.single().chainage in 77.0..80.0)
        assertEquals(1,match(evidence(),listOf(b,a)).size)
    }
    @Test fun parallelOffsetAndCrossing() {
        assertTrue(match(evidence(lat=45.00012)).isEmpty())
        assertTrue(match(evidence(bearing=0.0)).isEmpty())
    }
    @Test fun reverseDirectionAndOneWay() {
        assertTrue(match(evidence(oneway="yes"),listOf(b,a)).isEmpty())
        assertTrue(match(evidence(direction="backward")).isEmpty())
    }
    @Test fun repeatedPassages() {
        assertEquals(2,match(evidence(),listOf(a,b,a)).size)
    }
    @Test fun nextIsAheadOnly() {
        val m = match(evidence())
        assertNotNull(SpeedPackRouteMatcher.next(m,0.0))
        assertNull(SpeedPackRouteMatcher.next(m,90.0))
        assertNull(SpeedPackRouteMatcher.next(m,null))
    }
}
