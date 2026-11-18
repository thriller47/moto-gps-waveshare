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
        assertEquals(185f,
            NavigationHeadingPolicy.movingHeading(15f, 365f, 15.0, 185f)!!, 0.001f)
    }

    @Test fun displacementCourseOnlyWhenGpsCourseUnavailable() {
        assertEquals(270f,
            NavigationHeadingPolicy.movingHeading(12f, null, 15.0, -90f)!!, 0.001f)
        assertNull(NavigationHeadingPolicy.movingHeading(12f, null, 2.0, 90f))
        assertNull(NavigationHeadingPolicy.movingHeading(12f, Float.NaN, 0.0, null))
    }

    @Test fun lowSpeedAndInvalidHeadingCannotRotateTheMap() {
        assertNull(NavigationHeadingPolicy.movingHeading(1.49f, 180f, 10.0, 180f))
        assertNull(NavigationHeadingPolicy.movingHeading(1.99f, 180f, 10.0, 180f))
        assertNull(NavigationHeadingPolicy.movingHeading(Float.NaN, 90f, 10.0, 90f))
        assertNull(NavigationHeadingPolicy.movingHeading(4f, Float.NaN, 3.9, 120f))
        assertEquals(270f,
            NavigationHeadingPolicy.movingHeading(4f, null, 12.0, -90f)!!, 0.001f)
    }

    @Test fun lowAccuracyCourseIsIgnoredAndFallbackRequiresRealMovement() {
        assertNull(NavigationHeadingPolicy.movingHeading(4f, 90f, 3.0, 270f, 50f))
        assertEquals(270f,
            NavigationHeadingPolicy.movingHeading(4f, 90f, 15.0, 270f, 50f)!!, 0.001f)
        assertNull(NavigationHeadingPolicy.movingHeading(1.5f, 90f, 15.0, 270f, 1f))
        assertNull(NavigationHeadingPolicy.movingHeading(1.8f, 90f, 2.0, 270f, 15f))
        assertEquals(90f,
            NavigationHeadingPolicy.movingHeading(2.0f, 90f, 2.0, 270f, 15f)!!, 0.001f)
        assertNull(NavigationHeadingPolicy.movingHeading(1.8f, null, 9.5, -90f, null, 2f))
        assertNull(NavigationHeadingPolicy.movingHeading(2.0f, null, 8.5, -90f, null, 2f))
        assertEquals(270f,
            NavigationHeadingPolicy.movingHeading(2.0f, null, 9.5, -90f, null, 2f)!!, 0.001f)
        assertNull(NavigationHeadingPolicy.movingHeading(5f, -1f, 8.0, 10f))
    }

    @Test fun stopHoldsLastCourseInsteadOfFollowingPhoneMagnetometer() {
        assertNull(NavigationHeadingPolicy.movingHeading(0f, 270f, 30.0, 270f))
        assertNull(NavigationHeadingPolicy.movingHeading(1.0f, 90f, 30.0, 90f))
        assertNull(NavigationHeadingPolicy.movingHeading(1.9f, 90f, 30.0, 90f))
    }
}

// A single displacement close to horizontal GNSS noise is not heading.
class HeadingDisplacementQualityTest {
    @Test fun fallbackRequiresHorizontalAccuracyAtMostThirdOfTravel() {
        assertNull(NavigationHeadingPolicy.movingHeading(
            8f, null, 12.0, 90f, null, 5f))
        assertEquals(90f, NavigationHeadingPolicy.movingHeading(
            8f, null, 18.0, 90f, null, 4f)!!, 0.001f)
        assertEquals(45f, NavigationHeadingPolicy.movingHeading(
            8f, 45f, 12.0, 90f, null, 7f)!!, 0.001f)
    }
}
