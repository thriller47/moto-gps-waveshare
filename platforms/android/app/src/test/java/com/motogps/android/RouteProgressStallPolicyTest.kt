package com.motogps.android

import com.motogps.android.viewmodel.RouteProgressStallPolicy
import org.junit.Assert.*
import org.junit.Test

class RouteProgressStallPolicyTest {
    // Synthetic route at 45N: no actual rider's coordinates in GitHub.
    private fun lat(northM: Int) = 45.0 + northM / 111_195.0
    private fun observe(
        policy: RouteProgressStallPolicy, tSec: Int, northM: Int,
        progressM: Double, routeToken: Long = 7L,
        speedMps: Float = 15f, course: Float? = 0f,
        accuracyM: Float = 4f, crossM: Double = 4.0
    ) = policy.observe(
        routeToken, tSec * 1_000L,
        lat(northM), 9.0, progressM,
        speedMps, course, accuracyM, crossM)

    @Test fun normalOnRouteProgressNeverTriggers() {
        val policy = RouteProgressStallPolicy()
        for (sec in 0..34)
            assertFalse(observe(policy, sec, sec * 15, sec * 15.0))
    }

    @Test fun catchesRecordedPatternButOnlyAfterSustainedMovement() {
        val policy = RouteProgressStallPolicy()
        var firstAt = -1
        for (sec in 0..24) {
            val pending = observe(policy, sec, sec * 15, 3.0)
            if (pending && firstAt == -1) firstAt = sec
        }
        assertTrue("No recovery on >180m of motion with ~3m progress", firstAt >= 12)
        assertTrue(firstAt <= 16)
        assertTrue(observe(policy, 25, 25 * 15, 3.0))
        // No second false request when the new route is installed.
        policy.onRouteChanged()
        assertFalse(observe(policy, 26, 0, 0.0, routeToken = 8L))
    }

    @Test fun resumingNaturalProgressCancelsPendingReroute() {
        val policy = RouteProgressStallPolicy()
        var pending = false
        for (sec in 0..14) pending = observe(policy, sec, sec * 15, 3.0)
        assertTrue(pending)
        assertFalse(observe(policy, 15, 225, 110.0))
    }

    @Test fun stopsJitterTeleportAndPoorGpsAreIgnored() {
        val policy = RouteProgressStallPolicy()
        for (sec in 0..30)
            assertFalse(observe(policy, sec, sec % 5, 3.0,
                speedMps = 0f))
        policy.reset()
        assertFalse(observe(policy, 0, 0, 3.0))
        assertFalse(observe(policy, 1, 160, 3.0)) // implausible 1s jump
        assertFalse(observe(policy, 2, 175, 3.0))
        policy.reset()
        for (sec in 0..25)
            assertFalse(observe(policy, sec, sec * 15, 3.0,
                accuracyM = 26f))
    }

    @Test fun farFromRouteOrNoCourseDoNotTrigger() {
        val policy = RouteProgressStallPolicy()
        for (sec in 0..24)
            assertFalse(observe(policy, sec, sec * 15, 3.0, crossM = 60.0))
        policy.reset()
        for (sec in 0..24)
            assertFalse(observe(policy, sec, sec * 15, 3.0, course = null))
    }

    @Test fun turnAndReturnCannotAccumulateOneFalseStraightWindow() {
        val policy = RouteProgressStallPolicy()
        for (sec in 0..20) {
            val north = if (sec <= 10) sec * 16 else (20 - sec) * 16
            val course = if (sec <= 10) 0f else 180f
            assertFalse(observe(policy, sec, north, 3.0, course = course))
        }
    }

    @Test fun freshRouteRetainsCooldownButNewJourneyCanRecover() {
        val policy = RouteProgressStallPolicy()
        for (sec in 0..16) observe(policy, sec, sec * 15, 3.0)
        policy.onRouteChanged()
        for (sec in 20..48)
            assertFalse(observe(policy, sec, sec * 15, 3.0, routeToken = 9L))
        policy.reset()
        var confirmed = false
        for (sec in 160..176)
            confirmed = observe(policy, sec, (sec - 160) * 15, 3.0,
                routeToken = 10L) || confirmed
        assertTrue(confirmed)
    }
}
