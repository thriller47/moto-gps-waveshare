package com.motogps.android.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationPositionPredictionTest {
    @Test fun stationaryFixNeverMoves() {
        val p = NavigationPositionPrediction.estimate(45.0, 9.0, 0f, 90f, 4f, 200, 200)
        assertEquals(0.0, p.extrapolatedMeters, 0.0)
    }

    @Test fun staleOrLowAccuracyFixNeverMoves() {
        assertEquals(0.0, NavigationPositionPrediction.estimate(
            45.0, 9.0, 20f, 90f, 4f, 1600, 1600).extrapolatedMeters, 0.0)
        assertEquals(0.0, NavigationPositionPrediction.estimate(
            45.0, 9.0, 20f, 90f, 40f, 200, 200).extrapolatedMeters, 0.0)
    }

    @Test fun estimateIsShortAndInTravelDirection() {
        val p = NavigationPositionPrediction.estimate(45.0, 9.0, 20f, 90f, 4f, 600, 600)
        assertEquals(12.0, p.extrapolatedMeters, 0.01)
        assertTrue(p.longitude > 9.0)
        assertTrue(kotlin.math.abs(p.latitude - 45.0) < 0.00001)
    }

    @Test fun missingHeadingDoesNotPredict() {
        val p = NavigationPositionPrediction.estimate(45.0, 9.0, 20f, null, 4f, 500, 500)
        assertEquals(0.0, p.extrapolatedMeters, 0.0)
    }
}
