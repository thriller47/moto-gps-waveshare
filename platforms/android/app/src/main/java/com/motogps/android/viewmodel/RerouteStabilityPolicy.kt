package com.motogps.android.viewmodel

import kotlin.math.cos
import kotlin.math.hypot

/**
 * Prevent a newly adopted Valhalla route from instantly invalidating itself.
 * A real large off-route deviation still takes precedence over the hold.
 * All times are monotonic (SystemClock.elapsedRealtime), never wall clock.
 */
internal class RerouteStabilityPolicy {
    private var acceptedAtMs = -1L
    private var acceptedLat = Double.NaN
    private var acceptedLon = Double.NaN

    fun reset() {
        acceptedAtMs = -1L
        acceptedLat = Double.NaN
        acceptedLon = Double.NaN
    }

    fun accepted(nowMs: Long, lat: Double, lon: Double) {
        acceptedAtMs = nowMs
        acceptedLat = lat
        acceptedLon = lon
    }

    fun shouldDefer(
        nowMs: Long, lat: Double, lon: Double,
        distanceFromRouteM: Double, reverseOnly: Boolean
    ): Boolean {
        if (acceptedAtMs < 0L || nowMs < acceptedAtMs) return false
        // A major, real departure always remains eligible for fast rerouting.
        if (distanceFromRouteM >= 100.0) return false
        val age = nowMs - acceptedAtMs
        if (age < 12_000L) return true
        if (!reverseOnly || age >= 45_000L) return false
        if (age < 20_000L) return true

        // Do not recalculate for a transient opposite edge at the same
        // junction. Genuine reverse travel can trigger after moving away.
        val dx = (lon - acceptedLon) * 111_195.0 *
            cos(Math.toRadians((lat + acceptedLat) / 2))
        val dy = (lat - acceptedLat) * 111_195.0
        return !dx.isFinite() || !dy.isFinite() || hypot(dx, dy) < 40.0
    }
}
