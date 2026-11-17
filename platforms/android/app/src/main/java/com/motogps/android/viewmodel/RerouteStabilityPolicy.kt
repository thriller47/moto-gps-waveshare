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
    private var previousCourseDeg: Float? = null
    private var previousCourseMs = -1L
    private var turnGuardUntilMs = -1L

    /** Only accepted moving GNSS courses, not stale BLE/compass headings. */
    fun observeCourse(nowMs: Long, courseDeg: Float?, speedMps: Float) {
        if (courseDeg == null || !courseDeg.isFinite() ||
            courseDeg < 0f || courseDeg >= 360f ||
            !speedMps.isFinite() || speedMps < 3f) return
        val previous = previousCourseDeg
        if (previous != null && previousCourseMs >= 0L &&
            nowMs >= previousCourseMs && nowMs - previousCourseMs <= 2_500L) {
            val delta = kotlin.math.abs(
                ((courseDeg - previous + 540f) % 360f) - 180f)
            if (delta >= 14f) turnGuardUntilMs = nowMs + 6_000L
        }
        previousCourseDeg = courseDeg
        previousCourseMs = nowMs
    }

    // A true >=100 m departure always remains eligible for fast rerouting.
    fun shouldDeferWhileTurning(nowMs: Long, distanceFromRouteM: Double) =
        nowMs >= 0L && turnGuardUntilMs >= nowMs &&
            distanceFromRouteM < 100.0

    fun reset() {
        acceptedAtMs = -1L
        acceptedLat = Double.NaN
        acceptedLon = Double.NaN
        previousCourseDeg = null
        previousCourseMs = -1L
        turnGuardUntilMs = -1L
    }

    fun accepted(nowMs: Long, lat: Double, lon: Double) {
        acceptedAtMs = nowMs
        acceptedLat = lat
        acceptedLon = lon
        turnGuardUntilMs = -1L
    }

    fun shouldDefer(
        nowMs: Long, lat: Double, lon: Double,
        distanceFromRouteM: Double, reverseOnly: Boolean
    ): Boolean {
        if (acceptedAtMs < 0L || nowMs < acceptedAtMs) return false
        // A major, real departure always remains eligible for fast rerouting.
        if (distanceFromRouteM >= 100.0) return false
        val age = nowMs - acceptedAtMs

        if (!reverseOnly) {
            // 17:37:01 -> 17:37:19 were two false, near-junction reroutes.
            return age < 25_000L
        }

        // reverseOnly = true
        if (age >= 45_000L) return false
        if (age < 20_000L) return true

        // Do not recalculate for a transient opposite edge at the same
        // junction. Genuine reverse travel can trigger after moving away.
        val dx = (lon - acceptedLon) * 111_195.0 *
            cos(Math.toRadians((lat + acceptedLat) / 2))
        val dy = (lat - acceptedLat) * 111_195.0
        return !dx.isFinite() || !dy.isFinite() || hypot(dx, dy) < 40.0
    }
}
