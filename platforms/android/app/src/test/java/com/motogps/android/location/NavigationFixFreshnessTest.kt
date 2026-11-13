package com.motogps.android.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationFixFreshnessTest {
    @Test fun noAcceptedFixIsAlwaysStale() {
        assertTrue(NavigationFixFreshness.isStale(false, 0L))
        assertTrue(NavigationFixFreshness.isStale(false, 1_000L))
    }

    @Test fun oneHertzGpsRemainsUsableBetweenFixes() {
        assertFalse(NavigationFixFreshness.isStale(true, 0L))
        assertFalse(NavigationFixFreshness.isStale(true, 1_000L))
        assertFalse(NavigationFixFreshness.isStale(true, 3_000L))
    }

    @Test fun oldOrFutureFixCannotRefreshEspPrediction() {
        assertTrue(NavigationFixFreshness.isStale(true, 3_001L))
        assertTrue(NavigationFixFreshness.isStale(true, 30_000L))
        assertTrue(NavigationFixFreshness.isStale(true, -1L))
    }
}
