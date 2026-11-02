package com.motogps.android.location

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** A strictly bounded display-only estimate; never use for rerouting or arrival detection. */
internal object NavigationPositionPrediction {
    data class Point(val latitude: Double, val longitude: Double, val extrapolatedMeters: Double)
    fun estimate(
        latitude: Double, longitude: Double, speedMps: Float, bearingDegrees: Float?,
        accuracyMeters: Float, fixAgeMs: Long, nowSinceFixMs: Long
    ): Point {
        val original = Point(latitude, longitude, 0.0)
        if (!latitude.isFinite() || !longitude.isFinite() ||
            latitude !in -85.0..85.0 || longitude !in -180.0..180.0 ||
            bearingDegrees == null || !bearingDegrees.isFinite() ||
            !speedMps.isFinite() || speedMps !in 3f..35f ||
            !accuracyMeters.isFinite() || accuracyMeters > 25f ||
            fixAgeMs !in 0L..1500L || nowSinceFixMs !in 0L..1500L) return original
        // Ignore the measured GNSS age above 350 ms: extrapolate only a small
        // increment, capped at 12 m, to avoid turning/stop overshoot.
        val seconds = (nowSinceFixMs.coerceAtMost(700L) / 1000.0)
        val meters = (speedMps * seconds).coerceAtMost(12.0)
        if (meters < 0.2) return original
        val earth = 6371000.0
        val angle = meters / earth
        val bearing = Math.toRadians(bearingDegrees.toDouble())
        val lat = Math.toRadians(latitude)
        val lon = Math.toRadians(longitude)
        val resultLat = asin(sin(lat) * cos(angle) + cos(lat) * sin(angle) * cos(bearing))
        val resultLon = lon + atan2(sin(bearing) * sin(angle) * cos(lat),
            cos(angle) - sin(lat) * sin(resultLat))
        return Point(Math.toDegrees(resultLat), Math.toDegrees(resultLon), meters)
    }
}
