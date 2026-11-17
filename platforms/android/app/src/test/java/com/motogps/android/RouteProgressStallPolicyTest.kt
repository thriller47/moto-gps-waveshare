package com.motogps.android

import com.motogps.android.viewmodel.RouteProgressStallPolicy
import org.junit.Assert.*
import org.junit.Test

class RouteProgressStallPolicyTest {
    // Synthetic geography only: no original GPS coordinates in source control.
    private fun lat(m: Int) = 45.0 + m / 111_195.0
    private fun check(
        tracker: RouteProgressStallPolicy,
        sec: Int, north: Int, progress: Double,
        globalCross: Double = 5.0, localCross: Double = 80.0,
        route: Long = 1L, course: Float? = 0f,
        speed: Float = 15f, accuracy: Float = 4f
    ) = tracker.observe(route, sec * 1000L, lat(north), 9.0,
        progress, speed, course, accuracy, globalCross, localCross)

    @Test fun frozenAnchorOnLaterRouteLegProducesDebouncedRerouteSignal() {
        val p = RouteProgressStallPolicy()
        assertFalse(check(p, 0, 0, 3.0))
        assertFalse(check(p, 1, 15, 3.0))
        assertFalse(check(p, 2, 30, 3.0))
        assertTrue(check(p, 3, 45, 3.0))
        assertTrue(check(p, 4, 60, 3.0)) // holds across existing debounce
        assertFalse(check(p, 5, 75, 65.0)) // genuine matcher recovery cancels
    }

    @Test fun ordinaryRouteProgressNeverTriggers() {
        val p = RouteProgressStallPolicy()
        for (s in 0..25)
            assertFalse(check(p, s, s * 15, s * 15.0))
    }

    @Test fun nearCurrentCorridorOrFarFromWholeRouteCannotTrigger() {
        val p = RouteProgressStallPolicy()
        for (s in 0..14)
            assertFalse(check(p, s, s * 15, 3.0,
                localCross = 10.0))
        p.reset()
        for (s in 0..14)
            assertFalse(check(p, s, s * 15, 3.0,
                globalCross = 55.0, localCross = 90.0))
        // Genuine conventional off-route detection covers the second case.
    }

    @Test fun noisyStationaryCourseMissingAndBadAccuracyDoNotTrigger() {
        val p = RouteProgressStallPolicy()
        for (s in 0..14)
            assertFalse(check(p, s, s % 4, 3.0, speed = 0f))
        p.reset()
        for (s in 0..14)
            assertFalse(check(p, s, s * 15, 3.0, course = null))
        p.reset()
        for (s in 0..14)
            assertFalse(check(p, s, s * 15, 3.0, accuracy = 24f))
        p.reset()
        assertFalse(check(p, 0, 0, 3.0))
        assertFalse(check(p, 1, 170, 3.0)) // impossible GPS teleport
        assertFalse(check(p, 2, 185, 3.0))
    }

    @Test fun aRoundaboutOrUturnBreaksTheStraightEvidenceWindow() {
        val p = RouteProgressStallPolicy()
        assertFalse(check(p, 0, 0, 3.0, course = 0f))
        assertFalse(check(p, 1, 12, 3.0, course = 15f))
        assertFalse(check(p, 2, 24, 3.0, course = 50f))
        assertFalse(check(p, 3, 36, 3.0, course = 105f))
        assertFalse(check(p, 4, 48, 3.0, course = 180f))
    }

    @Test fun freshRouteDoesNotInheritEvidenceAndHasNoTwoMinuteLockout() {
        val p = RouteProgressStallPolicy()
        for (s in 0..3) check(p, s, s * 15, 3.0)
        assertTrue(check(p, 4, 60, 3.0))
        p.onRouteChanged()
        assertFalse(check(p, 90, 0, 2.0, route = 2L))
        assertFalse(check(p, 91, 15, 2.0, route = 2L))
        assertFalse(check(p, 92, 30, 2.0, route = 2L))
        // After 90s and a legitimate NEW route, recovery is permitted.
        assertTrue(check(p, 93, 45, 2.0, route = 2L))
    }

    @Test fun briefHeadingGlitchResetsConsecutiveGoodFixes() {
        val p = RouteProgressStallPolicy()
        assertFalse(check(p, 0, 0, 3.0))
        assertFalse(check(p, 1, 15, 3.0))
        assertFalse(check(p, 2, 30, 3.0, course = 95f))
        assertFalse(check(p, 3, 45, 3.0))
        assertFalse(check(p, 4, 60, 3.0))
        assertFalse(check(p, 5, 75, 3.0))
        assertTrue(check(p, 6, 90, 3.0))
    }
}
