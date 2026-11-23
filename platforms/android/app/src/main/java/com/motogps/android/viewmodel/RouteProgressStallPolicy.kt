package com.motogps.android.viewmodel

import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Detect a frozen matched corridor, not an arbitrary difference between
 * GPS travel and route progress.
 *
 * A loop can place a *future* leg close to GPS while the old progress anchor
 * remains hundreds of metres away. Global route proximity says "on route";
 * meanwhile the matcher refuses to jump, so guidance gets stuck.
 *
 * A confirmed mismatch permits a normal Valhalla reroute. It never advances
 * the matcher or skips mandatory waypoints, and has NO 120-second lockout:
 * standard GPS/off-route reroute cooldowns remain in the caller.
 */
internal class RouteProgressStallPolicy {
    private data class Fix(
        val atMs: Long, val lat: Double, val lon: Double,
        val progressM: Double, val courseDeg: Float
    )
    private val fixes = ArrayDeque<Fix>()
    private var routeToken: Long? = null
    private var confirmedUntilMs = -1L
    private var confirmedProgressM = Double.NaN

    private companion object {
        const val MIN_SPEED_MPS = 4f
        const val MAX_ACCURACY_M = 20f
        const val MAX_GLOBAL_CROSS_TRACK_M = 25.0
        const val MIN_CORRIDOR_CROSS_TRACK_M = 65.0
        const val MIN_CROSS_TRACK_DIFFERENCE_M = 40.0
        const val MAX_COURSE_CHANGE_DEG = 45.0
        const val MAX_FIX_INTERVAL_MS = 3_500L
        const val MAX_FIX_STEP_M = 55.0
        const val MIN_STEP_M = 2.0
        const val MIN_FIX_COUNT = 4
        const val MIN_DURATION_MS = 2_500L
        const val MIN_TRAVELLED_M = 30.0
        const val MIN_DISPLACEMENT_M = 25.0
        const val MAX_PROGRESS_GAIN_M = 12.0
        const val LATCH_MS = 15_000L
    }

    fun reset() = onRouteChanged()

    fun onRouteChanged() {
        fixes.clear()
        routeToken = null
        confirmedUntilMs = -1L
        confirmedProgressM = Double.NaN
    }

    /**
     * Invoke once per accepted GPS fix, not each BLE/snapshot refresh.
     * The matched corridor distance comes from the FIRST ~100m ahead of the
     * matched progress, with 30m allowance behind it. Global cross-track
     * uses the complete Valhalla route, as before.
     */
    fun observe(
        token: Long, nowMs: Long, lat: Double, lon: Double,
        matchedProgressM: Double?, speedMps: Float,
        courseDeg: Float?, accuracyM: Float,
        globalCrossTrackM: Double, matchedCorridorCrossTrackM: Double
    ): Boolean {
        if (routeToken != token) {
            onRouteChanged()
            routeToken = token
        }
        val effectiveCorridorCross = if (matchedCorridorCrossTrackM == Double.POSITIVE_INFINITY &&
            matchedProgressM?.isFinite() == true) 999.0 else matchedCorridorCrossTrackM
        val good = nowMs >= 0L && lat.isFinite() && lon.isFinite() &&
            matchedProgressM?.isFinite() == true &&
            speedMps.isFinite() && speedMps >= MIN_SPEED_MPS &&
            courseDeg != null && courseDeg.isFinite() &&
            courseDeg >= 0f && courseDeg < 360f &&
            accuracyM.isFinite() && accuracyM in 0f..MAX_ACCURACY_M &&
            globalCrossTrackM.isFinite() &&
            globalCrossTrackM <= MAX_GLOBAL_CROSS_TRACK_M &&
            effectiveCorridorCross.isFinite() &&
            effectiveCorridorCross >= MIN_CORRIDOR_CROSS_TRACK_M &&
            effectiveCorridorCross - globalCrossTrackM >= MIN_CROSS_TRACK_DIFFERENCE_M
        if (confirmedUntilMs >= nowMs) {
            val currentProgress = matchedProgressM
            if (currentProgress != null && currentProgress.isFinite() &&
                currentProgress > confirmedProgressM + 50.0) {
                confirmedUntilMs = -1L
                fixes.clear()
                return false
            }
            // Keep confirmed latch alive across transient single-fix speed/accuracy dips
            // unless we are clearly back near the matched corridor.
            if (effectiveCorridorCross.isFinite() && effectiveCorridorCross < 30.0) {
                confirmedUntilMs = -1L
                fixes.clear()
                return false
            }
            return true
        }
        if (!good) {
            fixes.clear()
            confirmedUntilMs = -1L
            return false
        }
        val progress = matchedProgressM!!
        val heading = courseDeg!!

        val previous = fixes.peekLast()
        if (previous != null) {
            val elapsed = nowMs - previous.atMs
            val travelled = metres(previous.lat, previous.lon, lat, lon)
            val courseChange = degreesApart(heading, previous.courseDeg)
            val windowChange = degreesApart(heading, fixes.first.courseDeg)
            if (elapsed <= 0L || elapsed > MAX_FIX_INTERVAL_MS ||
                travelled > MAX_FIX_STEP_M ||
                courseChange > MAX_COURSE_CHANGE_DEG ||
                windowChange > MAX_COURSE_CHANGE_DEG) {
                fixes.clear()
            } else if (travelled < MIN_STEP_M) {
                return false
            }
        }
        fixes.addLast(Fix(nowMs, lat, lon, progress, heading))
        // Keep state bounded if a noisy receiver never accumulates a
        // sufficient net displacement despite reporting moving speed.
        while (!fixes.isEmpty() &&
            (fixes.size > 20 ||
                nowMs - fixes.first.atMs > 15_000L)) fixes.removeFirst()
        if (fixes.size < MIN_FIX_COUNT) return false

        val first = fixes.first
        if (nowMs - first.atMs < MIN_DURATION_MS) return false
        if (progress - first.progressM > MAX_PROGRESS_GAIN_M) {
            // The matcher is moving: this is not the frozen-anchor defect.
            fixes.clear()
            fixes.addLast(Fix(nowMs, lat, lon, progress, heading))
            return false
        }
        var travelled = 0.0
        var prior = first
        for (fix in fixes) {
            if (fix !== first) travelled += metres(prior.lat, prior.lon, fix.lat, fix.lon)
            prior = fix
        }
        val displacement = metres(first.lat, first.lon, lat, lon)
        if (travelled < MIN_TRAVELLED_M ||
            displacement < MIN_DISPLACEMENT_M) return false

        confirmedProgressM = progress
        confirmedUntilMs = nowMs + LATCH_MS
        fixes.clear()
        return true
    }

    private fun degreesApart(a: Float, b: Float) =
        abs((a - b + 540.0) % 360.0 - 180.0)

    private fun metres(lat1: Double, lon1: Double, lat2: Double, lon2: Double) =
        hypot((lat2 - lat1) * 111_195.0,
            (lon2 - lon1) * 111_195.0 *
                cos(Math.toRadians((lat1 + lat2) / 2.0)))
}
