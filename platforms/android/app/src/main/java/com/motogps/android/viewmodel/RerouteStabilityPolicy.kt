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
    private var credibleDepartureSinceMs = -1L
    private var lastCredibleDepartureMs = -1L

    private companion object {
        // A 45 m threshold can still be a false junction snap. At 60+ metres
        // with accurate GNSS, however, sustained off-route travel must not be
        // blocked indefinitely by repeated heading changes in a roundabout.
        const val CONFIRMED_DEPARTURE_METRES = 60.0
        const val MAX_FIX_ACCURACY_METRES = 12.0F
        const val CONFIRMATION_MS = 2_000L
        const val MAX_FIX_GAP_MS = 2_500L
    }

    /** Only accepted moving GNSS courses, not stale BLE/compass headings. */
    fun observeCourse(nowMs: Long, courseDeg: Float?, speedMps: Float) {
        if (courseDeg == null || !courseDeg.isFinite() ||
            courseDeg < 0f || courseDeg > 360f ||
            !speedMps.isFinite() || speedMps < 3f) return
        val normalized = if (courseDeg == 360f) 0f else courseDeg
        val previous = previousCourseDeg
        if (previous != null && previousCourseMs >= 0L &&
            nowMs >= previousCourseMs && nowMs - previousCourseMs <= 2_500L) {
            val delta = kotlin.math.abs(
                ((normalized - previous + 540f) % 360f) - 180f)
            val dtSec = ((nowMs - previousCourseMs) / 1000.0).coerceAtLeast(0.15)
            // Support both 1 Hz fixes (delta >= 14 deg) and 5 Hz fixes (turn rate >= 25 deg/s).
            if (delta >= 14f || (delta >= 4.5f && delta / dtSec >= 25.0)) {
                turnGuardUntilMs = nowMs + 6_000L
            }
        }
        previousCourseDeg = normalized
        previousCourseMs = nowMs
    }

    /**
     * Preserve the turn guard for ambiguous junctions, but release it when
     * accurate fixes sustain a substantial departure for >=2 seconds.
     * Repeated GNSS course changes cannot restart this independent clock.
     * This is only a guard bypass: NavigationViewModel still enforces its
     * consecutive-fix debounce, route-acceptance hold and reroute cooldown.
     */
    fun shouldDeferWhileTurning(
        nowMs: Long, distanceFromRouteM: Double,
        accuracyM: Float = Float.POSITIVE_INFINITY
    ): Boolean {
        val credibleDeparture = nowMs >= 0L &&
            distanceFromRouteM.isFinite() &&
            distanceFromRouteM >= CONFIRMED_DEPARTURE_METRES &&
            accuracyM.isFinite() && accuracyM in 0.0F..MAX_FIX_ACCURACY_METRES
        if (!credibleDeparture) {
            credibleDepartureSinceMs = -1L
            lastCredibleDepartureMs = -1L
        } else {
            if (credibleDepartureSinceMs < 0L ||
                lastCredibleDepartureMs < 0L ||
                nowMs < lastCredibleDepartureMs ||
                nowMs - lastCredibleDepartureMs > MAX_FIX_GAP_MS) {
                credibleDepartureSinceMs = nowMs
            }
            lastCredibleDepartureMs = nowMs
        }

        if (nowMs < 0L || turnGuardUntilMs < nowMs ||
            distanceFromRouteM >= 100.0) return false

        val confirmedDeparture = credibleDeparture &&
            nowMs - credibleDepartureSinceMs >= CONFIRMATION_MS
        return !confirmedDeparture
    }

    fun reset() {
        acceptedAtMs = -1L
        acceptedLat = Double.NaN
        acceptedLon = Double.NaN
        previousCourseDeg = null
        previousCourseMs = -1L
        turnGuardUntilMs = -1L
        credibleDepartureSinceMs = -1L
        lastCredibleDepartureMs = -1L
    }

    fun accepted(nowMs: Long, lat: Double, lon: Double) {
        acceptedAtMs = nowMs
        acceptedLat = lat
        acceptedLon = lon
        previousCourseDeg = null
        previousCourseMs = -1L
        turnGuardUntilMs = -1L
        credibleDepartureSinceMs = -1L
        lastCredibleDepartureMs = -1L
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
