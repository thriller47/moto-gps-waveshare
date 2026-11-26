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
    @Test fun rawPreviewIncludesLongSegmentAndIgnoresDirection() {
        val end = SpeedPackRouteMatcher.Point(45.0,7.01)
        val raw = evidence(lat=45.0002,bearing=0.0,oneway="yes")
        assertTrue(SpeedPackRouteMatcher.match(listOf(a,end),listOf(raw)).isEmpty())
        val nearby = SpeedPackRouteMatcher.nearby(listOf(a,end),listOf(raw))
        assertEquals(1,nearby.size)
        assertTrue(nearby.single().crossTrack in 20.0..24.0)
        assertTrue(SpeedPackRouteMatcher.nearby(listOf(a,end),
            listOf(evidence(lat=45.005))).isEmpty())
    }

    @Test fun widePreviewKeepsClosestOriginalPointAndDoesNotRelaxStrictMatcher() {
        val raw = evidence(lat=45.003)
        assertTrue(match(raw).isEmpty())
        val nearby = SpeedPackRouteMatcher.nearby(listOf(a,b,a),listOf(raw))
        assertEquals(1,nearby.size)
        assertTrue(nearby.single().crossTrack in 333.0..335.0)
        val displaced = evidence().copy(originalPoint = raw.originalPoint)
        assertEquals(1,match(displaced).size)
        assertTrue(SpeedPackRouteMatcher.nearby(listOf(a,b),listOf(displaced))
            .single().crossTrack in 333.0..335.0)
    }

    @Test fun nearOneMeterFoundWithBothRadiiAndAllWaysRetained() {
        val original = evidence(lat=45.000009).copy(way=0L)
        val narrow = SpeedPackRouteMatcher.nearby(listOf(a,b),listOf(original),
            radiusMeters=50.0)
        val wide = SpeedPackRouteMatcher.nearby(listOf(a,b),listOf(original),
            radiusMeters=500.0)
        assertEquals(1,narrow.size)
        assertEquals(wide,narrow)
        assertTrue(narrow.single().crossTrack in .9..1.1)
        val ways = listOf(
            evidence().copy(way=11,originalPoint=original.point),
            evidence(lat=45.0001,bearing=0.0).copy(way=12,originalPoint=original.point),
            evidence(lat=45.00017).copy(way=13,originalPoint=original.point,grade=true)
        )
        val candidates = SpeedPackRouteMatcher.previewCandidates(listOf(a,b),narrow,ways)
        assertEquals(setOf(11L,12L,13L),candidates.map { it.evidence.way }.toSet())
        assertEquals(1,candidates.map { it.segmentIndex }.distinct().size)
    }

    @Test fun wideOnlyRecordDoesNotBecomeWayCandidate() {
        val raw = evidence(lat=45.003)
        val nearby = SpeedPackRouteMatcher.nearby(listOf(a,b),listOf(raw))
        assertEquals(1,nearby.size)
        assertTrue(SpeedPackRouteMatcher.previewCandidates(listOf(a,b),nearby,listOf(raw)).isEmpty())
    }

    @Test fun nextIsAheadOnly() {
        val m = match(evidence())
        assertNotNull(SpeedPackRouteMatcher.next(m,0.0))
        assertNull(SpeedPackRouteMatcher.next(m,90.0))
        assertNull(SpeedPackRouteMatcher.next(m,null))
    }
}
