package com.motogps.android

import com.motogps.android.viewmodel.RerouteStabilityPolicy
import org.junit.Assert.*
import org.junit.Test

class RerouteStabilityPolicyTest {
    @Test fun realLargeOffRouteDepartureNeverGetsSuppressed() {
        val p = RerouteStabilityPolicy()
        p.accepted(1_000L, 45.0, 9.0)
        assertFalse(p.shouldDefer(2_000L, 45.0, 9.0, 105.0, false))
        assertFalse(p.shouldDefer(2_000L, 45.0, 9.0, 120.0, true))
    }

    @Test fun recentRerouteGetsShortHoldThenRecovers() {
        val p = RerouteStabilityPolicy()
        p.accepted(1_000L, 45.0, 9.0)
        assertTrue(p.shouldDefer(10_000L, 45.0, 9.0, 60.0, false))
        assertTrue(p.shouldDefer(13_100L, 45.0, 9.0, 60.0, false))
        assertTrue(p.shouldDefer(19_000L, 45.0, 9.0, 62.0, false))
        assertFalse(p.shouldDefer(26_100L, 45.0, 9.0, 60.0, false))
    }

    @Test fun reverseHoldRequiresTimeAndMovementAndAlwaysExpires() {
        val p = RerouteStabilityPolicy()
        p.accepted(1_000L, 45.0, 9.0)
        val moved60m = 45.0 + 60.0 / 111_195.0
        assertTrue(p.shouldDefer(17_000L, moved60m, 9.0, 1.0, true))
        assertTrue(p.shouldDefer(25_000L, 45.0, 9.0, 1.0, true))
        assertFalse(p.shouldDefer(25_000L, moved60m, 9.0, 1.0, true))
        assertFalse(p.shouldDefer(47_000L, 45.0, 9.0, 1.0, true))
    }

    @Test fun movingTurnDefersBriefMapMismatchButNotLargeDeparture() {
        val p = RerouteStabilityPolicy()
        p.observeCourse(1_000L, 20f, 8f)
        assertFalse(p.shouldDeferWhileTurning(1_500L, 81.0))
        p.observeCourse(2_000L, 48f, 8f)
        assertTrue(p.shouldDeferWhileTurning(7_500L, 81.0))
        assertFalse(p.shouldDeferWhileTurning(7_500L, 105.0))
        assertFalse(p.shouldDeferWhileTurning(8_001L, 81.0))
        p.observeCourse(12_000L, 200f, 8f) // too old to infer a turn
        assertFalse(p.shouldDeferWhileTurning(12_100L, 81.0))
        p.reset()
        assertFalse(p.shouldDeferWhileTurning(12_100L, 81.0))
    }

    @Test fun acceptedClearsTurnGuardImmediately() {
        val p = RerouteStabilityPolicy()
        p.observeCourse(1_000L, 20f, 8f)
        p.observeCourse(2_000L, 48f, 8f) // Turn detected: turnGuardUntilMs = 8_000L
        assertTrue(p.shouldDeferWhileTurning(3_000L, 50.0))
        p.accepted(3_000L, 45.0, 9.0) // New route accepted -> turnGuardUntilMs reset to -1
        assertFalse(p.shouldDeferWhileTurning(3_000L, 50.0))
    }

    @Test fun resetOrClockRollbackNeverBlocksNavigation() {
        val p = RerouteStabilityPolicy()
        p.accepted(9_000L, 45.0, 9.0)
        assertFalse(p.shouldDefer(1_000L, 45.0, 9.0, 0.0, true))
        p.reset()
        assertFalse(p.shouldDefer(9_100L, 45.0, 9.0, 0.0, true))
    }
}
