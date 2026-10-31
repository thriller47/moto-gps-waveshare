package com.motogps.android.viewmodel

import kotlin.math.abs

/** Avoid treating movement backwards along the same OSM segment as on-route. */
internal object RouteDirectionPolicy {
    fun isOpposite(
        travelBearing: Float?,
        routeBearing: Float,
        speedMetersPerSecond: Float,
        distanceToRouteMeters: Double,
        segmentLengthMeters: Double
    ): Boolean {
        if (travelBearing == null || !travelBearing.isFinite() ||
            !routeBearing.isFinite() || !speedMetersPerSecond.isFinite() ||
            speedMetersPerSecond < 4f || distanceToRouteMeters > 25.0 ||
            segmentLengthMeters < 12.0) return false
        val delta = abs(((travelBearing - routeBearing + 540f) % 360f) - 180f)
        return delta >= 135f
    }
}
