package com.motogps.android.viewmodel

/**
 * Large BLE scenes are still rate-limited; only the final approach to an
 * explicitly identified roundabout gets a shorter moving refresh interval.
 */
internal object MapSceneRefreshPolicy {
    data class Limits(val minimumIntervalMs: Long, val movementThresholdM: Double)

    fun limits(maneuverName: String?, distanceToManeuverM: Long,
               roundaboutExit: Int): Limits {
        val nearTurn = maneuverName != null &&
            maneuverName !in setOf("continue", "none", "arrive") &&
            distanceToManeuverM in 0L..260L
        val nearRoundabout = nearTurn && roundaboutExit > 0 &&
            distanceToManeuverM <= 180L
        return when {
            nearRoundabout -> Limits(2_500L, 18.0)
            nearTurn -> Limits(3_500L, 30.0)
            else -> Limits(6_000L, 60.0)
        }
    }
}
