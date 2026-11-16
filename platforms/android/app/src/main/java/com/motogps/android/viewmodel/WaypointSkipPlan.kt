package com.motogps.android.viewmodel

/**
 * Skipping removes exactly one intermediate via point, never the final
 * destination (which is stored separately from the waypoint list).
 * Completed waypoint progress is preserved without marking a skip as reached.
 */
internal data class WaypointSkipPlan(
    val skipped: PlaceSearchResult,
    val pending: List<PlaceSearchResult>,
    val revised: List<PlaceSearchResult>
) {
    companion object {
        fun create(stops: List<PlaceSearchResult>, nextIndex: Int): WaypointSkipPlan? {
            if (nextIndex !in stops.indices) return null
            return WaypointSkipPlan(
                skipped = stops[nextIndex],
                pending = stops.drop(nextIndex + 1),
                revised = stops.filterIndexed { i, _ -> i != nextIndex }
            )
        }
    }
}
