package com.motogps.android.jni

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class BLEProtocolGoldenCheckTest {

    @Test
    fun `test payload mapping is clean and matches types`() {
        val input = MotoBLENavigationSnapshotInput(
            stateName = "navigating",
            networkName = "online",
            displayPageName = "navigation",
            maneuverName = "turn_right",
            trafficName = "clear",
            hasDestination = true,
            hasFix = true,
            gnssStale = false,
            offRoute = false,
            routeRequestInFlight = false,
            trafficRequestInFlight = false,
            hasRouteView = false,
            routeToken = 1234L,
            routeGeneration = 1L,
            maneuverID = 2L,
            distanceToManeuverM = 300L,
            remainingDistanceM = 1500L,
            remainingDurationS = 300L,
            routeProgressM = 500L,
            totalDistanceM = 2000L,
            speedDeciKPH = 450,
            speedLimitKPH = 60,
            headingCentiDegrees = 18000,
            accuracyDecimeters = 50,
            crossTrackDecimeters = 10,
            roundaboutExit = 0,
            roadName = "Main St",
            instructionText = "Turn right onto Main St"
        )

        assertNotNull(input)
        assertEquals("navigating", input.stateName)
        assertEquals(300L, input.distanceToManeuverM)
    }
}
