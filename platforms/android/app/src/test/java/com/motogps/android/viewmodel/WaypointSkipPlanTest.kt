package com.motogps.android.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WaypointSkipPlanTest {
    private fun stop(name: String, n: Double) =
        PlaceSearchResult(name, "", "Tour circolare", 45.0 + n, 9.0)

    @Test fun skipFirstPreservesLaterWaypoints() {
        val work = stop("Lavoro", 0.01)
        val lake = stop("Lago", 0.02)
        val mountain = stop("Passo", 0.03)
        val plan = requireNotNull(WaypointSkipPlan.create(listOf(work, lake, mountain), 0))
        assertEquals(work, plan.skipped)
        assertEquals(listOf(lake, mountain), plan.pending)
        assertEquals(plan.pending, plan.revised)
    }

    @Test fun skipAfterCompletedKeepsCompletedHistoryAndPending() {
        val work = stop("Lavoro", 0.01)
        val lake = stop("Lago", 0.02)
        val mountain = stop("Passo", 0.03)
        val plan = requireNotNull(WaypointSkipPlan.create(listOf(work, lake, mountain), 1))
        assertEquals(lake, plan.skipped)
        assertEquals(listOf(work, mountain), plan.revised)
        assertEquals(listOf(mountain), plan.pending)
    }

    @Test fun finalDestinationCannotBeSkipped() {
        assertNull(WaypointSkipPlan.create(emptyList(), 0))
        assertNull(WaypointSkipPlan.create(listOf(stop("Lavoro", 0.01)), 1))
        assertNull(WaypointSkipPlan.create(listOf(stop("Lavoro", 0.01)), -1))
    }
}
