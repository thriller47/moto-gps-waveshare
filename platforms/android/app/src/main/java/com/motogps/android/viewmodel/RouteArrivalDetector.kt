package com.motogps.android.viewmodel

/**
 * Detect arrival at the FINAL destination, never at an intermediate stop.
 *
 * Uses already-filtered GPS positions and remaining road distance so a
 * hairpin that passes near the destination early does not end navigation.
 * Requires two separate accepted fixes at least 1.2 s apart. No time-based
 * auto-stop: the rider remains on the arrived page until they end the route.
 */
internal class RouteArrivalDetector {
    private var firstNearbyFixMs = -1L
    private var lastNearbyFixMs = -1L

    fun reset() {
        firstNearbyFixMs = -1L
        lastNearbyFixMs = -1L
    }

    fun update(
        distanceToDestinationMeters: Double,
        remainingRouteMeters: Long,
        pendingIntermediateStops: Int,
        nowMs: Long
    ): Boolean {
        if (pendingIntermediateStops != 0 ||
            !distanceToDestinationMeters.isFinite() ||
            distanceToDestinationMeters > 40.0 ||
            remainingRouteMeters < 0L || remainingRouteMeters > 125L ||
            nowMs < 0L) {
            reset()
            return false
        }
        if (firstNearbyFixMs < 0L || nowMs < lastNearbyFixMs ||
            nowMs - lastNearbyFixMs > 6_000L) {
            firstNearbyFixMs = nowMs
            lastNearbyFixMs = nowMs
            return false
        }
        lastNearbyFixMs = nowMs
        return nowMs - firstNearbyFixMs >= 1_200L
    }
}
