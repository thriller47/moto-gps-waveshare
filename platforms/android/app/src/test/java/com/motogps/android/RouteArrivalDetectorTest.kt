package com.motogps.android

import com.motogps.android.viewmodel.RouteArrivalDetector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteArrivalDetectorTest {
    @Test fun arrivalNeedsTwoSeparatedReliableNearFixes() {
        val detector = RouteArrivalDetector()
        assertFalse(detector.update(27.0, 80, 0, 1000))
        assertFalse(detector.update(21.0, 55, 0, 1800))
        assertTrue(detector.update(18.0, 30, 0, 2400))
    }

    @Test fun intermediateStopNeverTriggersFinalArrival() {
        val detector = RouteArrivalDetector()
        assertFalse(detector.update(15.0, 35, 1, 1000))
        assertFalse(detector.update(8.0, 10, 1, 2800))
        assertFalse(detector.update(8.0, 10, 0, 3000))
        assertTrue(detector.update(8.0, 10, 0, 4300))
    }

    @Test fun roadRemainingDistancePreventsEarlyArrivalOnHairpin() {
        val detector = RouteArrivalDetector()
        assertFalse(detector.update(18.0, 2000, 0, 1000))
        assertFalse(detector.update(10.0, 1350, 0, 2600))
        assertFalse(detector.update(20.0, 100, 0, 3800))
        assertTrue(detector.update(14.0, 50, 0, 5100))
    }

    @Test fun leavingArrivalAreaOrLosingGpsContinuityResetsDetection() {
        val detector = RouteArrivalDetector()
        assertFalse(detector.update(20.0, 70, 0, 1000))
        assertFalse(detector.update(70.0, 65, 0, 2500))
        assertFalse(detector.update(20.0, 60, 0, 3200))
        assertFalse(detector.update(20.0, 60, 0, 10_000))
        assertTrue(detector.update(20.0, 60, 0, 11_300))
        detector.reset()
        assertFalse(detector.update(5.0, 10, 0, 12_000))
    }

    @Test fun invalidGpsNeverDeclaresArrival() {
        val detector = RouteArrivalDetector()
        assertFalse(detector.update(Double.NaN, 10, 0, 1000))
        assertFalse(detector.update(Double.POSITIVE_INFINITY, 10, 0, 2800))
        assertFalse(detector.update(9.0, -1, 0, 4000))
    }
}
