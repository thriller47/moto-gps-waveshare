package com.motogps.android.viewmodel

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SpeedPackWayVerifierTest {
    private val a = SpeedPackRouteMatcher.Point(0.0,0.0)
    private val b = SpeedPackRouteMatcher.Point(0.0,.0001)
    private val c = SpeedPackRouteMatcher.Point(0.0,.0002)
    private val points = listOf(a,b,c)
    private fun edge(way: Long,begin: Int,end: Int) = JSONObject()
        .put("way_id",way).put("begin_shape_index",begin).put("end_shape_index",end)
    // polyline6: (0,0), (0,.0001), (0,.0002)
    private fun response(vararg edges: JSONObject) = JSONObject()
        .put("shape","???gE?gE").put("edges",JSONArray(edges.toList()))

    @Test fun matchingSegments() {
        assertArrayEquals(longArrayOf(11,22),
            SpeedPackWayVerifier.segmentWays(points,response(edge(11,0,1),edge(22,1,2))))
    }
    @Test fun missingOrConflictingSpan() {
        assertNull(SpeedPackWayVerifier.segmentWays(points,response(edge(11,0,1))))
        assertNull(SpeedPackWayVerifier.segmentWays(points,response(edge(11,0,2),edge(22,1,2))))
    }
    @Test fun changedShapeRejected() {
        assertNull(SpeedPackWayVerifier.segmentWays(listOf(a,b,SpeedPackRouteMatcher.Point(0.0,.0003)),
            response(edge(11,0,2))))
    }
}
