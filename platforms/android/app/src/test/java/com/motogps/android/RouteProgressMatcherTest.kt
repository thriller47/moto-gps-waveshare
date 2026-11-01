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
