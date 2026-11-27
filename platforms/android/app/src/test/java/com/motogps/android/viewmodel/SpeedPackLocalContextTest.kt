package com.motogps.android.viewmodel

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class SpeedPackLocalContextTest {
    private fun point(m: Double) = SpeedPackRouteMatcher.Point(45.0,7.0+m/(111195.0*kotlin.math.cos(Math.toRadians(45.0))))
    private fun spot(id: String,m: Double) = SpeedPackRouteMatcher.Evidence(
        "osm",id,0,point(m),90.0,null)
    private fun data(vararg raw: SpeedPackRouteMatcher.Evidence) =
        SpeedPackLocalData(raw.toList(),raw.map { it.copy(way=42L) },emptySet())
    private val points = (0..100).map { point(it*100.0) }

    @Test fun rollingContextExcludesDistantRouteAndPreservesAbsoluteChainage() {
        val d = data(spot("near",600.0),spot("later",3100.0),spot("far",8500.0))
        val r = SpeedPackLocalRoute(points)
        val first = r.prepare(d,0.0,{})
        assertEquals(setOf("near"),first.candidates.map { it.evidence.id }.toSet())
        val moved = r.prepare(d,2500.0,{})
        assertTrue(moved.firstSegment > 0)
        assertEquals(setOf("later"),moved.candidates.map { it.evidence.id }.toSet())
        assertTrue(moved.candidates.any { kotlin.math.abs(it.chainage+moved.offset-3100.0)<1.0 })
    }

    @Test fun sameStaticContextWorksAgainAfterRouteResetAndReversal() {
        val d = data(spot("camera",600.0))
        val out = SpeedPackLocalRoute(points.take(11)).prepare(d,0.0,{})
        val back = SpeedPackLocalRoute(points.take(11).reversed()).prepare(d,0.0,{})
        assertTrue(out.candidates.any { it.evidence.id=="camera" && kotlin.math.abs(it.chainage-600.0)<1.0 })
        assertTrue(back.candidates.any { it.evidence.id=="camera" && kotlin.math.abs(it.chainage-400.0)<1.0 })
    }

    @Test fun invalidTraceDoesNotAuthorizeLocalAlert() {
        val r = SpeedPackLocalRoute(points.take(11))
        val plan = r.prepare(data(spot("camera",600.0)),0.0,{})
        val result = r.verify(plan,true,{}) { null }
        assertTrue(result.candidates > 0)
        assertTrue(result.deviceAlerts.isEmpty())
    }

    @Test fun successfulItalianExactTraceIsReusedAndGivesAbsoluteChainage() {
        val a=SpeedPackRouteMatcher.Point(0.0,0.0)
        val b=SpeedPackRouteMatcher.Point(0.0,.0001)
        val c=SpeedPackRouteMatcher.Point(0.0,.0002)
        val raw=SpeedPackRouteMatcher.Evidence("osm","a",0,b,90.0,null)
        val r=SpeedPackLocalRoute(listOf(a,b,c))
        val plan=r.prepare(data(raw),0.0,{})
        val response=JSONObject().put("shape","???gE?gE")
            .put("edges",JSONArray().put(JSONObject().put("way_id",42)
                .put("begin_shape_index",0).put("end_shape_index",2)))
            .put("admins",JSONArray().put(JSONObject().put("country_code","IT")))
        val near=r.verify(plan,true,{}) { response }
        val full=r.verify(plan,false,{}) { error("trace already cached") }
        assertTrue(near.deviceAlerts.isNotEmpty())
        assertEquals(near.deviceAlerts,full.deviceAlerts)
        assertEquals(0,full.nativeCalls)
    }
}
