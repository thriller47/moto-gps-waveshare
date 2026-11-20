package com.motogps.android.ble

/** Short BLE scan windows with a bounded backoff while the rider is away. */
internal object BleReconnectPolicy {
    const val SCAN_WINDOW_MS = 12_000L
    private val intervals = longArrayOf(1_500L, 3_000L, 6_000L, 12_000L, 30_000L)

    fun delayMillis(failedAttempts: Int): Long =
        intervals[failedAttempts.coerceIn(0, intervals.lastIndex)]
}
