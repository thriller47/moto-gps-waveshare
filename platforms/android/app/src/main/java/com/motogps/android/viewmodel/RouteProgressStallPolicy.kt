package com.motogps.android.viewmodel

import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Detects sustained route-progress starvation while good moving GPS fixes
 * remain near SOME leg of the full route. This is a reroute signal only:
 * never jump the matcher to a speculative future leg or skip a waypoint.
 *
 * On the 17 Nov replay the rider moved >1 km while progress stayed near 3m.
 * A whole-shape off-route check missed it because the later leg was nearby.
 */
internal class RouteProgressStallPolicy {
    private data class Fix(
        val timeMs: Long, val lat: Double, val lon: Double,
        val progressM: Double, val courseDeg: Float
    )
    private val fixes = ArrayDeque<Fix>()
    private var token: Long? = null
    private var confirmedAtMs = -1L
    private var confirmedUntilMs = -1L
    private var confirmedProgressM = Double.NaN

    private companion object {
        const val MIN_SPEED_MPS = 4f
        const val MAX_ACCURACY_M = 20f
        const val MAX_CROSS_TRACK_M = 25.0
        const val MAX_COURSE_CHANGE_DEG = 45.0
        const val MAX_FIX_STEP_M = 55.0
        const val WINDOW_MS = 30_000L
        const val MIN_SAMPLE_COUNT = 8
        const val MIN_DURATION_MS = 8_000L
        const val MIN_GPS_TRAVEL_M = 180.0
        const val MIN_DISPLACEMENT_M = 130.0
        const val MAX_PROGRESS_ADVANCE_M = 25.0
        const val LATCH_MS = 15_000L
        const val COOLDOWN_MS = 120_000L
    }

    /** Explicit new user navigation (not an automatic reroute). */
    fun reset() {
        onRouteChanged()
        confirmedAtMs = -1L
    }

    /** Retain anti-loop cooldown when adopting an automatic reroute. */
    fun onRouteChanged() {
        fixes.clear()
        token = null
        confirmedUntilMs = -1L
        confirmedProgressM = Double.NaN
    }

    /** Call once for each accepted GPS fix, not for each BLE snapshot. */
    fun observe(
        routeToken: Long, nowMs: Long,
        latitude: Double, longitude: Double,
        matchedProgressM: Double?, speedMps: Float,
        courseDegrees: Float?, accuracyMeters: Float,
        crossTrackMeters: Double
    ): Boolean {
        if (token != routeToken) {
            onRouteChanged()
            token = routeToken
        }
        val valid = nowMs >= 0L && latitude.isFinite() &&
            longitude.isFinite() && matchedProgressM?.isFinite() == true &&
            speedMps.isFinite() && speedMps >= MIN_SPEED_MPS &&
            courseDegrees != null && courseDegrees.isFinite() &&
            courseDegrees in 0f..359.999f &&
            accuracyMeters.isFinite() && accuracyMeters in 0f..MAX_ACCURACY_M &&
            crossTrackMeters.isFinite() && crossTrackMeters <= MAX_CROSS_TRACK_M
        if (!valid) {
            fixes.clear()
            confirmedUntilMs = -1L
            return false
        }
        val progress = matchedProgressM!!
        val course = courseDegrees!!
        if (confirmedUntilMs >= nowMs) {
            // A legitimate recovery before the reroute debounce completes
            // must cancel the pending request.
            if (progress > confirmedProgressM + 70.0) {
                confirmedUntilMs = -1L
                return false
            }
            return true
        }
        if (confirmedAtMs >= 0L &&
            nowMs - confirmedAtMs in 0L until COOLDOWN_MS) return false

        val previous = fixes.peekLast()
        if (previous != null) {
            val interval = nowMs - previous.timeMs
            val step = metres(previous.lat, previous.lon, latitude, longitude)
            val headingChange = abs(
                (course - previous.courseDeg + 540.0) % 360.0 - 180.0)
            val windowHeadingChange = abs(
                (course - fixes.peekFirst().courseDeg + 540.0) % 360.0 - 180.0)
            if (interval <= 0L || interval > 3_500L ||
                step > MAX_FIX_STEP_M || headingChange > MAX_COURSE_CHANGE_DEG ||
                windowHeadingChange > MAX_COURSE_CHANGE_DEG) {
                fixes.clear()
            } else if (step < 1.5) {
                return false // GPS noise / duplicate accepted fix
            }
        }
        fixes.addLast(Fix(nowMs, latitude, longitude, progress, course))
        while (!fixes.isEmpty() &&
            nowMs - fixes.peekFirst().timeMs > WINDOW_MS) fixes.removeFirst()
        if (fixes.size < MIN_SAMPLE_COUNT) return false
        val first = fixes.peekFirst()
        if (nowMs - first.timeMs < MIN_DURATION_MS) return false
        if (progress - first.progressM > MAX_PROGRESS_ADVANCE_M) {
            fixes.clear()
            fixes.addLast(Fix(nowMs, latitude, longitude, progress, course))
            return false
        }
        var travelled = 0.0
        var last: Fix? = null
        for (fix in fixes) {
            if (last != null)
                travelled += metres(last.lat, last.lon, fix.lat, fix.lon)
            last = fix
        }
        val net = metres(first.lat, first.lon, latitude, longitude)
        if (travelled < MIN_GPS_TRAVEL_M || net < MIN_DISPLACEMENT_M)
            return false

        confirmedAtMs = nowMs
        confirmedUntilMs = nowMs + LATCH_MS
        confirmedProgressM = progress
        fixes.clear()
        return true
    }

    private fun metres(lat1: Double, lon1: Double, lat2: Double, lon2: Double) =
        hypot((lat2 - lat1) * 111_195.0,
            (lon2 - lon1) * 111_195.0 *
                cos(Math.toRadians((lat1 + lat2) / 2.0)))
}
