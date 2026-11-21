package com.motogps.android.ble

/** System-managed recovery with filtered scan fallback and bounded backoff. */
internal object BleReconnectPolicy {
    const val SCAN_WINDOW_MS = 12_000L
    const val SYSTEM_CONNECT_WINDOW_MS = 30_000L
    const val HANDSHAKE_TIMEOUT_MS = 20_000L

    fun useSystemConnect(hasReadyDevice: Boolean, preferSystem: Boolean): Boolean =
        hasReadyDevice && preferSystem
    private val intervals = longArrayOf(1_500L, 3_000L, 6_000L, 12_000L, 30_000L)

    fun delayMillis(failedAttempts: Int): Long =
        intervals[failedAttempts.coerceIn(0, intervals.lastIndex)]
}
