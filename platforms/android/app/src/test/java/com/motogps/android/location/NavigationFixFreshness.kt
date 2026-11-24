package com.motogps.android.location

/**
 * Navigation fix freshness, using the GNSS fix's Android monotonic timestamp.
 *
 * A BLE route window can be resent without a new GNSS fix. Never present
 * that re-sent window to the ESP32 predictor as fresh location evidence.
 * The BLE snapshot's gnssStale flag is the compatibility-preserving guard;
 * the age itself is not yet transferred in the protocol.
 */
internal object NavigationFixFreshness {
    private const val STALE_AFTER_MS = 3_000L
    private const val CLOCK_SKEW_TOLERANCE_MS = 2_000L

    fun isStale(hasAcceptedFix: Boolean, ageMs: Long): Boolean =
        !hasAcceptedFix || ageMs !in 0L..STALE_AFTER_MS

    /**
     * Tolerates small negative clock skew between the GNSS HAL timestamp and
     * SystemClock.elapsedRealtime() (up to [CLOCK_SKEW_TOLERANCE_MS]) without
     * disabling ESP32 motion prediction, while still marking larger future
     * timestamps or aged fixes as stale.
     */
    fun isStaleWithSkewTolerance(hasAcceptedFix: Boolean, ageMs: Long): Boolean {
        val normalizedAgeMs = if (ageMs in -CLOCK_SKEW_TOLERANCE_MS until 0L) 0L else ageMs
        return isStale(hasAcceptedFix, normalizedAgeMs)
    }
}
