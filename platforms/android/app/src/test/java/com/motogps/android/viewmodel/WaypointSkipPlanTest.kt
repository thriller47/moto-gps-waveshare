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

    @Test fun skipOrdinaryRouteRetainsFinalDestinationAndFollowingStops() {
        // The final destination is held separately in NavigationViewModel.
        // WaypointSkipPlan must be independent of the circular-tour marker.
        val first = PlaceSearchResult("Passo", "", "Strada", 45.01, 9.01)
        val second = PlaceSearchResult("Lago", "", "Strada", 45.02, 9.02)
        val finalDestination = PlaceSearchResult("Arrivo", "", "Indirizzo", 45.03, 9.03)
        val plan = requireNotNull(WaypointSkipPlan.create(listOf(first, second), 0))
        assertEquals(first, plan.skipped)
        assertEquals(listOf(second), plan.pending)
        assertEquals(listOf(second), plan.revised)
        assertEquals("Arrivo", finalDestination.name)
    }

    @Test fun skipLastIntermediateOnOrdinaryRouteLeavesDirectDestination() {
        val stop = PlaceSearchResult("Sosta", "", "Indirizzo", 45.01, 9.01)
        val plan = requireNotNull(WaypointSkipPlan.create(listOf(stop), 0))
        assertEquals(stop, plan.skipped)
        assertEquals(emptyList<PlaceSearchResult>(), plan.pending)
        assertEquals(emptyList<PlaceSearchResult>(), plan.revised)
        assertNull(WaypointSkipPlan.create(listOf(stop), 1))
    }

    @Test fun finalDestinationCannotBeSkipped() {
        assertNull(WaypointSkipPlan.create(emptyList(), 0))
        assertNull(WaypointSkipPlan.create(listOf(stop("Lavoro", 0.01)), 1))
        assertNull(WaypointSkipPlan.create(listOf(stop("Lavoro", 0.01)), -1))
    }
}
