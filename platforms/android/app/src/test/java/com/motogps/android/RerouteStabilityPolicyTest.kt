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
        assertFalse(p.shouldDefer(13_100L, 45.0, 9.0, 60.0, false))
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

    @Test fun resetOrClockRollbackNeverBlocksNavigation() {
        val p = RerouteStabilityPolicy()
        p.accepted(9_000L, 45.0, 9.0)
        assertFalse(p.shouldDefer(1_000L, 45.0, 9.0, 0.0, true))
        p.reset()
        assertFalse(p.shouldDefer(9_100L, 45.0, 9.0, 0.0, true))
    }
}
