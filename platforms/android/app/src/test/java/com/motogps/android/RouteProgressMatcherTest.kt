package com.motogps.android

import com.motogps.android.jni.GeoPointE6
import com.motogps.android.viewmodel.RouteProgressMatcher
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos

class RouteProgressMatcherTest {
    private fun point(north: Int, east: Int = 0): GeoPointE6 =
        GeoPointE6(45_000_000 + (north * 1e6 / 111_195.0).toInt(),
            9_000_000 + (east * 1e6 /
                (111_195.0 * cos(Math.toRadians(45.0)))).toInt())
    private fun lat(north: Int) = 45.0 + north / 111_195.0
    private fun lon(east: Int) = 9.0 + east /
        (111_195.0 * cos(Math.toRadians(45.0)))

    @Test fun distanceOnLongSegmentUsesProjection() {
        val tracker = RouteProgressMatcher()
        tracker.reset(listOf(point(0), point(200), point(400)))
        val a = tracker.remaining(lat(50), 9.0, 0f, 12f)!!
        val b = tracker.remaining(lat(65), 9.0, 0f, 12f)!!
        assertEquals(350.0, a.toDouble(), 2.0)
        assertTrue(b < a)
    }

    @Test fun parallelOrBackwardsMatchingNeverIncreasesRemaining() {
        val tracker = RouteProgressMatcher()
        tracker.reset(listOf(point(0), point(150), point(150, 12), point(0, 12)))
        val a = tracker.remaining(lat(70), lon(1), 0f, 10f)!!
        val b = tracker.remaining(lat(85), lon(7), 0f, 10f)!!
        assertTrue(b <= a)
    }

    @Test fun trueReverseOnCurrentRoadIsStillDetected() {
        val tracker = RouteProgressMatcher()
        tracker.reset(listOf(point(0), point(250), point(500)))
        tracker.remaining(lat(130), 9.0, 0f, 12f)
        val reverse = tracker.reverseCandidate(lat(110), 9.0, 180f, 12f)
        assertNotNull(reverse)
        assertTrue(reverse!!.distanceMeters < 2.0)
        assertTrue(reverse.progressDeltaMeters < 0.0)
        assertNull(tracker.reverseCandidate(lat(110), 9.0, 0f, 12f))
    }

    @Test fun hairpinParallelRoadDoesNotTriggerFalseReverse() {
        val tracker = RouteProgressMatcher()
        tracker.reset(listOf(point(0), point(180), point(180, 12), point(0, 12)))
        tracker.remaining(lat(70), lon(0), 0f, 12f)
        assertNull(tracker.reverseCandidate(lat(95), lon(8), 0f, 12f))
    }

    @Test fun ambiguousNearbyOppositeLegYieldsToForwardLeg() {
        val tracker = RouteProgressMatcher()
        tracker.reset(listOf(point(0), point(60), point(60, 7),
            point(0, 7), point(0, 20)))
        tracker.remaining(lat(30), lon(0), 0f, 10f)
        // The return leg is only 1m away and lies within the progress
        // window, but the forward leg is still a plausible match at 6m.
        assertNull(tracker.reverseCandidate(lat(30), lon(6), 0f, 10f))
    }

    @Test fun remoteLoopEdgeCannotTriggerReverseNearOldProgress() {
        val tracker = RouteProgressMatcher()
        tracker.reset(listOf(point(0), point(150), point(150, 9),
            point(0, 9), point(0, 25)))
        tracker.remaining(lat(40), lon(0), 0f, 11f)
        // The geometrically closest *opposite* segment is hundreds of
        // metres further into the route, and is not the travelled edge.
        assertNull(tracker.reverseCandidate(lat(40), lon(9), 180f, 11f))
    }

    @Test fun reverseRequiresConfirmedSpeedAndProximity() {
        val tracker = RouteProgressMatcher()
        tracker.reset(listOf(point(0), point(250)))
        tracker.remaining(lat(120), 9.0, 0f, 12f)
        assertNull(tracker.reverseCandidate(lat(110), 9.0, null, 12f))
        assertNull(tracker.reverseCandidate(lat(110), 9.0, 180f, 2f))
        assertNull(tracker.reverseCandidate(lat(110), lon(36), 180f, 12f))
    }


    @Test fun firstMatchAfterRerouteStaysOnDepartureLegNotLaterNearbyLoop() {
        // The route doubles back beside its start. GPS is 1m from a future
        // northbound leg, but 24m from the current departure leg. Without a
        // first-fix anchor, the matcher immediately reported ~590m travelled.
        val tracker = RouteProgressMatcher()
        tracker.reset(listOf(
            point(0), point(220), point(220, 90),
            point(0, 90), point(0, 25), point(200, 25)))
        val first = tracker.remaining(lat(20), lon(24), 0f, 12f)!!
        assertTrue("First match jumped to a future loop leg",
            tracker.matchedProgressMeters()!! < 60.0)
        assertTrue(first > 730L)
        tracker.remaining(lat(36), lon(24), 0f, 12f)
        assertTrue(tracker.matchedProgressMeters()!! < 80.0)
    }

    @Test fun actualUturnDepartureIsNotOverriddenByAnAlignedLaterLeg() {
        // A mandatory turn-around can require heading south while the bike
        // still travels north. Do not snap directly onto the eventual north
        // leg merely because its bearing matches the GPS course.
        val tracker = RouteProgressMatcher()
        tracker.reset(listOf(
            point(0), point(-210), point(-210, 25),
            point(0, 25), point(200, 25)))
        tracker.remaining(lat(-15), lon(24), 0f, 11f)
        assertTrue(tracker.matchedProgressMeters()!! < 60.0)
    }

    @Test fun staleRerouteCanStartBeyondTheInitialRouteHorizon() {
        // A slow route response may arrive after the rider has travelled
        // farther than the departure safety radius. Preserve global matching.
        val tracker = RouteProgressMatcher()
        tracker.reset(listOf(point(0), point(400), point(600)))
        tracker.remaining(lat(240), lon(8), 0f, 19f)
        assertTrue(tracker.matchedProgressMeters()!! > 200.0)
    }

    @Test fun offRouteMotionFreezesDistanceUntilReroute() {
        val tracker = RouteProgressMatcher()
        tracker.reset(listOf(point(0), point(500)))
        val a = tracker.remaining(lat(100), 9.0, 0f, 15f)!!
        assertEquals(a, tracker.remaining(lat(110), lon(90), 90f, 15f))
        tracker.reset(listOf(point(0), point(600)))
        assertEquals(590.0,
            tracker.remaining(lat(10), 9.0, 0f, 15f)!!.toDouble(), 3.0)
    }
}
