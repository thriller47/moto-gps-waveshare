package com.motogps.android.diagnostics

import com.motogps.android.jni.MotoBLERouteGeometry
import com.motogps.android.routing.ValhallaTurnGuidance
import com.motogps.android.viewmodel.RouteProgressMatcher
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure JSON payload construction, deliberately separate from disk I/O.
 * Coordinate-bearing events stay in explicit private GPS replay recordings.
 */
internal object ReplayProgressPayload {
    fun fullRoute(
        route: MotoBLERouteGeometry, plan: ValhallaTurnGuidance.Plan
    ): JSONObject {
        val points = JSONArray()
        for (p in route.points) {
            points.put(JSONArray().put(p.latitudeE6 / 1e6).put(p.longitudeE6 / 1e6))
        }
        val actions = JSONArray()
        for (action in plan.actions) {
            actions.put(JSONObject()
                .put("id", action.index)
                .put("type", action.type)
                .put("along_m", action.shapeMeters)
                .put("release_m", action.releaseMeters)
                .put("exit", action.roundaboutExit))
        }
        return JSONObject()
            .put("type", "route_full")
            .put("route_token", route.routeToken)
            .put("generation", route.routeGeneration)
            .put("total_shape_m", plan.totalShapeMeters)
            .put("points", points)
            .put("actions", actions)
    }

    fun progress(
        lat: Double, lon: Double,
        routeToken: Long, generation: Long, speedMps: Float,
        travelHeading: Float?, rawRemainingM: Long, guardedRemainingM: Long,
        match: RouteProgressMatcher.MatchEvidence?,
        next: ValhallaTurnGuidance.Plan.Next?,
        totalShapeM: Double
    ): JSONObject {
        val e = JSONObject()
            .put("type", "progress_debug")
            .put("route_token", routeToken).put("generation", generation)
            .put("lat", lat).put("lon", lon)
            .put("speed_mps", if (speedMps.isFinite()) speedMps.toDouble() else -1.0)
            .put("raw_remaining_m", rawRemainingM)
            .put("guarded_remaining_m", guardedRemainingM)
            .put("plan_progress_m", (totalShapeM - guardedRemainingM)
                .coerceAtLeast(0.0))
            .put("match_mode", match?.mode ?: "unavailable")
            .put("next_type", next?.action?.type ?: "none")
            .put("next_id", next?.action?.index ?: 0L)
            .put("turn_m", next?.meters ?: 0L)
        if (travelHeading != null && travelHeading.isFinite())
            e.put("travel_heading_deg", travelHeading.toDouble())
        if (match != null) {
            e.put("matched_progress_m", match.selectedProgressM)
                .put("scored_candidate_m", match.scoredCandidateM)
                .put("nearest_cross_m", match.nearestCrossTrackM)
                .put("fix_step_m", match.fixStepM)
                .put("origin_gap_m", match.originGapM)
            match.previousProgressM?.let { e.put("previous_progress_m", it) }
            match.initialCandidateM?.let { e.put("initial_candidate_m", it) }
        }
        return e
    }
}
