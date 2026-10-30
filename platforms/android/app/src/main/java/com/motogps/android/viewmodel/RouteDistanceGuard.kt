package com.motogps.android.viewmodel

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Prevents a nearest-segment jump at a junction from skipping a maneuver
 * before the motorcycle physically reaches it.
 */
internal class RouteDistanceGuard {
    private var previousRemaining: Long? = null
    private var previousLat = Double.NaN
    private var previousLon = Double.NaN

    fun reset() {
        previousRemaining = null
        previousLat = Double.NaN
        previousLon = Double.NaN
    }

    fun stabilize(rawMeters: Long, latitude: Double, longitude: Double): Long {
        val raw = rawMeters.coerceAtLeast(0L)
        val previous = previousRemaining
        if (previous == null || !latitude.isFinite() || !longitude.isFinite()) {
            previousRemaining = raw
            previousLat = latitude
            previousLon = longitude
            return raw
        }
        val travelled = distanceMeters(previousLat, previousLon, latitude, longitude)
        // Repeated snapshots without new GPS movement cannot consume metres.
        val maxStep = if (travelled >= 0.75) travelled * 1.45 + 1.0 else 0.0
        val remaining = if (raw < previous) {
            max(raw.toDouble(), previous.toDouble() - maxStep)
        } else {
            min(raw.toDouble(), previous.toDouble() + max(5.0, maxStep))
        }.coerceAtLeast(0.0).toLong()
        previousRemaining = remaining
        previousLat = latitude
        previousLon = longitude
        return remaining
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        if (!lat1.isFinite() || !lon1.isFinite()) return 0.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = (sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)).coerceIn(0.0, 1.0)
        return 2.0 * 6_371_000.0 * asin(sqrt(a))
    }
}
