package com.motogps.android

import com.motogps.android.diagnostics.ReplayTraceSegmentPolicy
import org.junit.Assert.*
import org.junit.Test

class ReplayTraceSegmentPolicyTest {
    @Test fun everyHalfHourRotatesWithoutStoppingRecording() {
        val p = ReplayTraceSegmentPolicy
        assertFalse(p.rotate(p.PART_DURATION_MS - 1, 0, 100, 40))
        assertTrue(p.rotate(p.PART_DURATION_MS, 0, 100, 40))
        // Part two and part hundred rotate by the same per-part interval.
        val start = 99L * p.PART_DURATION_MS
        assertFalse(p.rotate(start + 59_000, start, 100, 40))
        assertTrue(p.rotate(start + p.PART_DURATION_MS, start, 100, 40))
    }

    @Test fun rotatesBeforeExceedingPartByteThreshold() {
        val p = ReplayTraceSegmentPolicy
        assertFalse(p.rotate(100, 0, p.PART_BYTES - 200, 199))
        assertTrue(p.rotate(100, 0, p.PART_BYTES - 200, 201))
        assertTrue(p.rotate(100, 0, p.PART_BYTES, 1))
    }

    @Test fun longSessionDoesNotHaveGlobalCutoff() {
        val p = ReplayTraceSegmentPolicy
        val dayMs = 24L * 60L * 60L * 1000L
        val newPartStart = dayMs + 1_000L
        assertFalse(p.rotate(dayMs + 2_000L, newPartStart, 100, 20))
        assertTrue(p.rotate(newPartStart + p.PART_DURATION_MS, newPartStart, 100, 20))
    }
}
