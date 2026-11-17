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
        displacementBearingDeg: Float?,
        bearingAccuracyDeg: Float? = null
    ): Float? {
        if (!speedMs.isFinite() || speedMs < 3.0f) return null
        val gps = if (gpsBearingDeg != null && gpsBearingDeg >= 0f &&
            gpsBearingDeg < 360f && (bearingAccuracyDeg == null ||
            (bearingAccuracyDeg.isFinite() && bearingAccuracyDeg <= 25f)))
            normalized(gpsBearingDeg) else null
        if (gps != null) return gps
        return if (displacementMeters.isFinite() && displacementMeters >= 12.0)
            normalized(displacementBearingDeg)
        else null
    }
}
