package com.motogps.android.viewmodel

import org.junit.Assert.*
import org.junit.Test

class SpeedPackAlertSelectionTest {
    private val point = SpeedPackRouteMatcher.Point(45.0,7.0)
    private fun hit(source: String,id: String,way: Long=10L,chain: Double=100.0,
                    direction: String?=null) =
        SpeedPackRouteMatcher.Match(SpeedPackRouteMatcher.Evidence(
            source,id,way,point,90.0,null,direction),chain,1.0,0)
    private fun select(matches: List<SpeedPackRouteMatcher.Match>,
                       hints: Set<SpeedPackAlertSelection.Hint> = emptySet()) =
        SpeedPackAlertSelection.select(matches,
            matches.associateWith { SpeedPackWayStatus.COINCIDENT },hints)

    @Test fun directionDoesNotExcludeVerifiedWay() {
        val a = hit("osm","1",direction="backward")
        assertEquals(1,select(listOf(a)).size)
        assertTrue(SpeedPackAlertSelection.select(listOf(a),
            mapOf(a to SpeedPackWayStatus.UNVERIFIED),emptySet()).isEmpty())
    }

    @Test fun onlyReciprocalSameWayNearbyPairMerges() {
        val a = hit("osm","1")
        val b = hit("mapatlas","x",chain=105.0)
        val hints = setOf(SpeedPackAlertSelection.Hint("1","x"))
        assertEquals(2,select(listOf(a,b)).size)
        val merged = select(listOf(a,b),hints)
        assertEquals(1,merged.size)
        assertEquals(setOf("osm","mapatlas"),merged.single().sources)
        assertEquals(2,select(listOf(a,b.copy(evidence=b.evidence.copy(way=11))),hints).size)
        assertEquals(2,select(listOf(a,b.copy(chainage=130.0)),hints).size)
    }

    @Test fun repeatedPassagesRemainAndNextIsAhead() {
        val a = hit("osm","1")
        val b = hit("mapatlas","x",chain=105.0)
        val hints = setOf(SpeedPackAlertSelection.Hint("1","x"))
        val plan = select(listOf(a,b,a.copy(chainage=700.0),b.copy(chainage=705.0)),hints)
        assertEquals(2,plan.size)
        assertEquals(700.0,SpeedPackAlertSelection.next(plan,200.0)!!.chainage,0.0)
        assertNull(SpeedPackAlertSelection.next(plan,701.0))
    }
}
