package com.motogps.android

import com.motogps.android.viewmodel.RouteDirectionPolicy
import org.junit.Assert.*
import org.junit.Test

class RouteDirectionPolicyTest {
    @Test fun detectsReverseMovementOnTheSameRoad() {
        assertTrue(RouteDirectionPolicy.isOpposite(270f, 90f, 12f, 5.0, 100.0))
        assertTrue(RouteDirectionPolicy.isOpposite(350f, 170f, 8f, 10.0, 60.0))
        assertFalse(RouteDirectionPolicy.isOpposite(90f, 90f, 12f, 5.0, 100.0))
    }

    @Test fun filtersStopsGpsNoiseAndShortCurvedSegments() {
        assertFalse(RouteDirectionPolicy.isOpposite(270f, 90f, 0f, 5.0, 100.0))
        assertFalse(RouteDirectionPolicy.isOpposite(270f, 90f, 12f, 40.0, 100.0))
        assertFalse(RouteDirectionPolicy.isOpposite(270f, 90f, 12f, 5.0, 7.0))
        assertFalse(RouteDirectionPolicy.isOpposite(null, 90f, 12f, 5.0, 100.0))
        assertFalse(RouteDirectionPolicy.isOpposite(220f, 90f, 12f, 5.0, 100.0))
    }
}
