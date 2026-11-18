package com.motogps.android

import com.motogps.android.diagnostics.ReplayProgressPayload
import com.motogps.android.jni.GeoPointE6
import com.motogps.android.jni.MotoBLERouteGeometry
import com.motogps.android.routing.ValhallaTurnGuidance
import com.motogps.android.viewmodel.RouteProgressMatcher
import org.junit.Assert.*
import org.junit.Test

class ReplayProgressPayloadTest {
    @Test fun fullRouteContainsEveryVertexAndExplicitManeuverOffsets() {
        val points = (0 until 725).map {
            GeoPointE6(45_000_000 + it * 10, 8_000_000 + it)
        }
        val route = MotoBLERouteGeometry(
            routeToken = 123L, routeGeneration = 2L,
            totalPointCount = points.size,
            viewOrigin = points.first(), points = points)
        val action = ValhallaTurnGuidance.Action(
            7L, 350.0, "roundabout", "", "", 3, 410.0)
        val plan = ValhallaTurnGuidance.Plan(725.0, listOf(action))
        val record = ReplayProgressPayload.fullRoute(route, plan)
        assertEquals("route_full", record.getString("type"))
        assertEquals(725, record.getJSONArray("points").length())
        assertEquals(45.0, record.getJSONArray("points")
            .getJSONArray(0).getDouble(0), 1e-6)
        assertEquals(7L, record.getJSONArray("actions")
            .getJSONObject(0).getLong("id"))
        assertEquals(350.0, record.getJSONArray("actions")
            .getJSONObject(0).getDouble("along_m"), 0.001)
        assertEquals(410.0, record.getJSONArray("actions")
            .getJSONObject(0).getDouble("release_m"), 0.001)
    }

    @Test fun progressRecordsRawGuardedMatcherAndGuidanceIndependently() {
        val evidence = RouteProgressMatcher.MatchEvidence(
            "hold_offroute", 3.0, 90.0, 3.0, 55.0,
            12.0, 17.0, null)
        val next = ValhallaTurnGuidance.Plan.Next(
            ValhallaTurnGuidance.Action(9L, 300.0, "roundabout",
                "", "", 2, 350.0), 297L)
        val record = ReplayProgressPayload.progress(
            45.0, 8.0, 77L, 2L, 10f, 45f, 997L, 999L,
            evidence, next, 1002.0)
        assertEquals("progress_debug", record.getString("type"))
        assertEquals(997L, record.getLong("raw_remaining_m"))
        assertEquals(999L, record.getLong("guarded_remaining_m"))
        assertEquals(3.0, record.getDouble("matched_progress_m"), 0.001)
        assertEquals(55.0, record.getDouble("nearest_cross_m"), 0.001)
        assertEquals("hold_offroute", record.getString("match_mode"))
        assertEquals("roundabout", record.getString("next_type"))
        assertEquals(297L, record.getLong("turn_m"))
        assertEquals(3.0, record.getDouble("plan_progress_m"), 0.001)
    }
}
