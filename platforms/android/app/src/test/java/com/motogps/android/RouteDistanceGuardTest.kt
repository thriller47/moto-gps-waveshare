package com.motogps.android

import com.motogps.android.viewmodel.RouteDistanceGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteDistanceGuardTest {
    @Test fun cornerSnapCannotConsumeThirtyMetresInOneFix() {
        val guard = RouteDistanceGuard()
        assertEquals(32L, guard.stabilize(32L, 45.0, 9.0))
        val first = guard.stabilize(0L, 45.000018, 9.0)
        assertTrue(first > 25L)
        assertEquals(first, guard.stabilize(0L, 45.000018, 9.0))
        val next = guard.stabilize(0L, 45.000090, 9.0)
        assertTrue(next < first && next > 0L)
    }

    @Test fun genuineMovementCanReachZeroAndRerouteResetsClock() {
        val guard = RouteDistanceGuard()
        assertEquals(30L, guard.stabilize(30L, 45.0, 9.0))
        assertEquals(0L, guard.stabilize(0L, 45.00030, 9.0))
        guard.reset()
        assertEquals(120L, guard.stabilize(120L, 45.1, 9.1))
    }
}
