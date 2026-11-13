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

    fun isStale(hasAcceptedFix: Boolean, ageMs: Long): Boolean =
        !hasAcceptedFix || ageMs !in 0L..STALE_AFTER_MS
}
