package com.motogps.android.viewmodel

/**
 * Transient, backwards-compatible marker in the existing BLE instruction.
 * Older firmware ignores the text; the ESP32 recognises the bounded prefix.
 * No new BLE packet type, maneuver, or route geometry is introduced.
 */
internal object WaypointReachedNotice {
    const val DURATION_MS = 7_000L
    const val PREFIX = "WP_REACHED:"

    fun message(index: Int, total: Int, startedMs: Long, nowMs: Long): String? {
        if (index !in 1..99 || total !in index..99 || startedMs <= 0L ||
            nowMs < startedMs || nowMs - startedMs >= DURATION_MS) return null
        return "$PREFIX$index/$total"
    }
}
