package com.motogps.android

import com.motogps.android.routing.ValhallaTurnGuidance
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ValhallaTurnGuidanceTest {
    private fun route(): JSONObject {
        val vertices = JSONArray()
        for (i in 0..6) vertices.put(JSONObject()
            .put("latitude_deg", 45.0)
            .put("longitude_deg", 9.0 + i * 0.001))
        val maneuvers = JSONArray()
            .put(JSONObject().put("id", 1).put("route_point_index", 0)
                .put("type", "continue").put("instruction", "Parti"))
            .put(JSONObject().put("id", 2).put("route_point_index", 2)
                .put("type", "turn_left").put("road_name", "Via Roma")
                .put("instruction", "Svolta a sinistra"))
            .put(JSONObject().put("id", 3).put("route_point_index", 4)
                .put("type", "turn_right").put("road_name", "Via Milano")
                .put("instruction", "Svolta a destra"))
            .put(JSONObject().put("id", 4).put("route_point_index", 6)
                .put("type", "arrive").put("instruction", "Arrivo"))
        return JSONObject().put("geometry_gcj02", vertices).put("maneuvers", maneuvers)
    }

    @Test fun nextTurnFollowsValhallaShapeNotStraightLine() {
        val plan = ValhallaTurnGuidance.fromRoute(route())
        assertEquals(3, plan.actions.size)
        assertEquals("turn_left", plan.next(plan.totalShapeMeters.toLong())?.action?.type)
        val atStart = plan.next(plan.totalShapeMeters.toLong())!!
        assertTrue(atStart.meters > 100L)
        val afterFirstTurn = plan.next(
            (plan.totalShapeMeters - plan.actions[0].shapeMeters - 25).toLong())!!
        assertEquals("turn_right", afterFirstTurn.action.type)
        assertEquals("Via Milano", afterFirstTurn.action.roadName)
        assertEquals(3L, afterFirstTurn.action.index)
    }

    @Test fun roundaboutRemainsActiveUntilExitAndKeepsExitNumber() {
        val route = route()
        val maneuvers = route.getJSONArray("maneuvers")
        maneuvers.getJSONObject(1).put("type", "roundabout")
            .put("roundabout_exit", 1)
            .put("roundabout_end_point_index", 3)
        val plan = ValhallaTurnGuidance.fromRoute(route)
        val inside = plan.next((plan.totalShapeMeters -
            (plan.actions[0].shapeMeters + plan.actions[1].shapeMeters) / 2).toLong())!!
        assertEquals("roundabout", inside.action.type)
        assertEquals(1, inside.action.roundaboutExit)
        assertEquals(0L, inside.meters)
        val passedExit = plan.next((plan.totalShapeMeters -
            plan.actions[0].releaseMeters - 20).toLong())!!
        assertEquals("turn_right", passedExit.action.type)
    }

    @Test fun finalArrivalIsNotPrematureAndDistanceClampsToZero() {
        val plan = ValhallaTurnGuidance.fromRoute(route())
        val nearFinish = plan.next(5L)!!
        assertEquals("arrive", nearFinish.action.type)
        assertTrue(nearFinish.meters in 0L..10L)
        assertNull(plan.next(-1))
    }

    @Test fun noManeuverPayloadDoesNotInventTurns() {
        val noSteps = JSONObject().put("geometry_gcj02",
            route().getJSONArray("geometry_gcj02"))
        val plan = ValhallaTurnGuidance.fromRoute(noSteps)
        assertTrue(plan.actions.isEmpty())
        assertNull(plan.next(120))
    }
}
