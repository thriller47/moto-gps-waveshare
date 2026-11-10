package com.motogps.android.diagnostics

/**
 * Each file is independently loadable by the browser simulator. These are
 * rotation thresholds, not recording/session limits: subsequent parts have
 * no fixed upper bound on count or total elapsed time.
 */
internal object ReplayTraceSegmentPolicy {
    const val PART_DURATION_MS = 30L * 60L * 1000L
    const val PART_BYTES = 12L * 1024L * 1024L

    fun rotate(elapsedMs: Long, partStartMs: Long,
               existingBytes: Long, nextLineBytes: Int): Boolean {
        return elapsedMs - partStartMs >= PART_DURATION_MS ||
            existingBytes + nextLineBytes > PART_BYTES
    }
}
