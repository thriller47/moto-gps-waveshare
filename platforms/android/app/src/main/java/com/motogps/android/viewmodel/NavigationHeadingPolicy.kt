package com.motogps.android.viewmodel

/** Direction-of-travel priority for the dashboard mounted on the motorcycle.
 *
 * The Android phone can move independently of the bike (seat, pocket, bag):
 * its magnetic azimuth must never replace the real travel course after a
 * valid GNSS fix, even while stopping at an intersection.
 */
internal object NavigationHeadingPolicy {
    fun normalized(degrees: Float?): Float? {
        if (degrees == null || !degrees.isFinite()) return null
        return (degrees % 360f + 360f) % 360f
    }

    fun usePhoneSensor(hasFirstFix: Boolean, isNavigating: Boolean): Boolean =
        !hasFirstFix && !isNavigating

    /** GNSS course wins; GPS displacement is fallback when course is absent. */
    fun movingHeading(
        speedMs: Float,
        gpsBearingDeg: Float?,
        displacementMeters: Double,
        displacementBearingDeg: Float?
    ): Float? {
        if (!speedMs.isFinite() || speedMs < 1.5f) return null
        val gps = normalized(gpsBearingDeg)
        if (gps != null) return gps
        return if (displacementMeters.isFinite() && displacementMeters >= 4.0)
            normalized(displacementBearingDeg)
        else null
    }
}
