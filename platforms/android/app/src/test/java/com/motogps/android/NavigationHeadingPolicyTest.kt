package com.motogps.android

import com.motogps.android.viewmodel.NavigationHeadingPolicy
import org.junit.Assert.*
import org.junit.Test

class NavigationHeadingPolicyTest {
    @Test fun phoneRotationMustNeverRotateNavigationMapAfterGpsFix() {
        assertTrue(NavigationHeadingPolicy.usePhoneSensor(false, false))
        assertFalse(NavigationHeadingPolicy.usePhoneSensor(true, false))
        assertFalse(NavigationHeadingPolicy.usePhoneSensor(false, true))
        assertFalse(NavigationHeadingPolicy.usePhoneSensor(true, true))
    }

    @Test fun gpsCourseWinsOverDisplacementWhenMoving() {
        assertEquals(90f,
            NavigationHeadingPolicy.movingHeading(20f, 90f, 30.0, 270f)!!, 0.001f)
        assertEquals(5f,
            NavigationHeadingPolicy.movingHeading(15f, 365f, 8.0, 185f)!!, 0.001f)
    }

    @Test fun displacementCourseOnlyWhenGpsCourseUnavailable() {
        assertEquals(270f,
            NavigationHeadingPolicy.movingHeading(12f, null, 8.0, -90f)!!, 0.001f)
        assertNull(NavigationHeadingPolicy.movingHeading(12f, null, 2.0, 90f))
        assertNull(NavigationHeadingPolicy.movingHeading(12f, Float.NaN, 0.0, null))
    }

    @Test fun lowSpeedAndInvalidHeadingCannotRotateTheMap() {
        assertNull(NavigationHeadingPolicy.movingHeading(1.49f, 180f, 10.0, 180f))
        assertNull(NavigationHeadingPolicy.movingHeading(Float.NaN, 90f, 10.0, 90f))
        assertNull(NavigationHeadingPolicy.movingHeading(4f, Float.NaN, 3.9, 120f))
        assertEquals(270f,
            NavigationHeadingPolicy.movingHeading(4f, null, 4.0, -90f)!!, 0.001f)
    }

    @Test fun stopHoldsLastCourseInsteadOfFollowingPhoneMagnetometer() {
        assertNull(NavigationHeadingPolicy.movingHeading(0f, 270f, 30.0, 270f))
        assertNull(NavigationHeadingPolicy.movingHeading(1.0f, 90f, 30.0, 90f))
    }
}
