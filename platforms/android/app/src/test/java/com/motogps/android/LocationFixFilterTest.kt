package com.motogps.android

import com.motogps.android.location.LocationFixFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationFixFilterTest {
    private fun fix(
        lat: Double = 45.0,
        lon: Double = 8.0,
        timestamp: Long = 100_000L,
        provider: String = "gps",
        accuracy: Float = 5f,
        speed: Float = 12f
    ) = LocationFixFilter.Fix(lat, lon, timestamp, accuracy, provider, speed)

    @Test
    fun rejects5001000And1500MeterTeleportsAndKeepsOriginalPosition() {
        for (jumpMeters in listOf(500, 1000, 1500)) {
            val filter = LocationFixFilter()
            assertTrue(filter.evaluate(fix(), 100_000L).accepted)

            // About one to three orders of magnitude faster than a bike.
            val latitudeJump = 45.0 + jumpMeters / 111_320.0
            val result = filter.evaluate(
                fix(lat = latitudeJump, timestamp = 102_000L),
                102_000L
            )
            assertFalse(result.accepted)
            assertEquals("implausible_jump", result.reason)

            // When the real GPS comes back after the spike, recovery is
            // immediate: the rejected sample never poisoned the baseline.
            val recovered = filter.evaluate(
                fix(lat = 45.00015, timestamp = 103_000L), 103_000L
            )
            assertTrue(recovered.accepted)
        }
    }

    @Test
    fun rejectsCoarseNetworkLocationsWhenGpsIsHealthy() {
        val filter = LocationFixFilter()
        assertTrue(filter.evaluate(fix(), 100_000L).accepted)
        assertEquals(
            "gps_has_priority",
            filter.evaluate(
                fix(lat = 45.0001, provider = "network",
                    accuracy = 25f, timestamp = 100_500L), 100_500L
            ).reason
        )
        assertTrue(filter.evaluate(
            fix(lat = 45.0002, timestamp = 101_000L), 101_000L
        ).accepted)
    }

    @Test
    fun allowsAccurateNetworkFallbackAfterGpsOutage() {
        val filter = LocationFixFilter()
        assertTrue(filter.evaluate(fix(), 100_000L).accepted)
        val fallback = filter.evaluate(
            fix(lat = 45.0002, timestamp = 113_000L,
                provider = "network", accuracy = 25f), 113_000L
        )
        assertTrue(fallback.accepted)
    }

    @Test
    fun rejectsStaleOutOfOrderAndInaccurateFixes() {
        val filter = LocationFixFilter()
        assertEquals(
            "stale_location",
            filter.evaluate(fix(timestamp = 80_000L), 100_000L).reason
        )
        assertEquals(
            "poor_accuracy",
            filter.evaluate(fix(accuracy = 180f), 100_000L).reason
        )
        assertTrue(filter.evaluate(fix(), 100_000L).accepted)
        assertEquals(
            "out_of_order",
            filter.evaluate(fix(lat = 45.0001), 100_100L).reason
        )
        assertEquals(
            "poor_accuracy",
            filter.evaluate(
                fix(timestamp = 101_000L, provider = "network",
                    accuracy = 250f), 101_000L
            ).reason
        )
    }

    @Test
    fun doesNotRejectRealMotorcycleTravelAfterLongGpsGap() {
        val filter = LocationFixFilter()
        assertTrue(filter.evaluate(fix(), 100_000L).accepted)
        // ~780m in 20 seconds: about 39m/s, plausible highway motion.
        assertTrue(filter.evaluate(
            fix(lat = 45.007, timestamp = 120_000L, speed = 39f),
            120_000L
        ).accepted)
    }

    @Test
    fun rejectsInvalidCoordinateAndFutureTimestamp() {
        val filter = LocationFixFilter()
        assertEquals(
            "invalid_coordinates",
            filter.evaluate(fix(lat = Double.NaN), 100_000L).reason
        )
        assertEquals(
            "stale_location",
            filter.evaluate(fix(timestamp = 105_000L), 100_000L).reason
        )
        assertEquals(
            "invalid_speed",
            filter.evaluate(fix(speed = Float.NaN), 100_000L).reason
        )
    }
}
