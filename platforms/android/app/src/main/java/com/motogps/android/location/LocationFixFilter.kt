package com.motogps.android.location

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Guards navigation/map origin against stale, coarse or physically impossible
 * locations. All timestamps use Android's monotonic elapsed-realtime clock,
 * NOT wall time (which can jump due to cellular/GNSS clock corrections).
 *
 * Rejected fixes never replace the last accepted rider position. A genuine
 * movement after a long GPS outage is allowed if its elapsed-time speed is
 * plausible; a one-off 500-1500 m network jump cannot blank the minimap.
 */
internal class LocationFixFilter {
    data class Fix(
        val latitude: Double,
        val longitude: Double,
        val elapsedRealtimeMs: Long,
        val accuracyMeters: Float,
        val provider: String,
        val speedMetersPerSecond: Float = 0f
    )

    data class Decision(
        val accepted: Boolean,
        val reason: String,
        val distanceMeters: Double = 0.0
    )

    private var previous: Fix? = null
    private var lastAcceptedGpsTimeMs: Long = -1L

    fun evaluate(fix: Fix, nowElapsedRealtimeMs: Long): Decision {
        if (!fix.latitude.isFinite() || !fix.longitude.isFinite() ||
            fix.latitude !in -90.0..90.0 || fix.longitude !in -180.0..180.0) {
            return Decision(false, "invalid_coordinates")
        }
        if (fix.elapsedRealtimeMs <= 0L || nowElapsedRealtimeMs < 0L) {
            return Decision(false, "invalid_timestamp")
        }
        val ageMs = nowElapsedRealtimeMs - fix.elapsedRealtimeMs
        if (ageMs < -2_000L || ageMs > 10_000L) {
            return Decision(false, "stale_location")
        }
        val isGps = fix.provider.equals("gps", ignoreCase = true)
        val accuracyLimit = if (isGps) 100f else 60f
        if (!fix.accuracyMeters.isFinite() || fix.accuracyMeters <= 0f ||
            fix.accuracyMeters > accuracyLimit) {
            return Decision(false, "poor_accuracy")
        }
        if (!fix.speedMetersPerSecond.isFinite() || fix.speedMetersPerSecond < 0f ||
            fix.speedMetersPerSecond > 90f) {
            return Decision(false, "invalid_speed")
        }

        val last = previous
        if (last != null && fix.elapsedRealtimeMs <= last.elapsedRealtimeMs) {
            return Decision(false, "out_of_order")
        }

        // GNSS is the authoritative motorcycle position. A coarse mobile
        // network fix must not replace a recently accepted GPS fix just
        // because its timestamp is a fraction of a second newer.
        if (!isGps && lastAcceptedGpsTimeMs >= 0L &&
            nowElapsedRealtimeMs - lastAcceptedGpsTimeMs in 0L..12_000L) {
            return Decision(false, "gps_has_priority")
        }

        var distance = 0.0
        if (last != null) {
            distance = distanceMeters(
                last.latitude, last.longitude, fix.latitude, fix.longitude
            )
            val seconds = (fix.elapsedRealtimeMs - last.elapsedRealtimeMs) / 1_000.0
            // 65 m/s is already ~234 km/h. The accuracy budget tolerates
            // regular GPS jitter without allowing kilometre-scale teleports.
            val accuracyBudget = maxOf(
                35.0, last.accuracyMeters.toDouble() + fix.accuracyMeters.toDouble()
            )
            if (distance > 65.0 * seconds + accuracyBudget) {
                return Decision(false, "implausible_jump", distance)
            }
        }

        previous = fix
        if (isGps) lastAcceptedGpsTimeMs = fix.elapsedRealtimeMs
        return Decision(true, "accepted", distance)
    }

    private fun distanceMeters(
        lat1: Double, lon1: Double, lat2: Double, lon2: Double
    ): Double {
        val latDiff = Math.toRadians(lat2 - lat1)
        val lonDiff = Math.toRadians(lon2 - lon1)
        val angle = sin(latDiff / 2.0) * sin(latDiff / 2.0) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(lonDiff / 2.0) * sin(lonDiff / 2.0)
        return 2.0 * 6_371_000.0 * asin(min(1.0, sqrt(angle)))
    }
}
