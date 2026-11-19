package com.motogps.android.viewmodel

import com.motogps.android.routing.RoutePreferences
import com.motogps.android.routing.RouteStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TourSessionStoreTest {
    private val home = PlaceSearchResult(
        "Casa", "", "Tour circolare", 45.0, 9.0)
    private val work = PlaceSearchResult(
        "Lavoro", "", "Ufficio", 45.15, 9.12)
    private val lake = PlaceSearchResult(
        "Lago", "", "Panorama", 45.25, 9.24)

    @Test fun overnightPausePreservesOnlyPendingStops() {
        val beforePause = TourSession(
            home, listOf(work, lake), completedStops = 1,
            RoutePreferences(style = RouteStyle.PANORAMIC, avoidHighways = true),
            pauseLatitude = 45.151, pauseLongitude = 9.121,
            updatedAtMillis = 123456789L, paused = true)
        val restored = TourSessionStore.decode(TourSessionStore.encode(beforePause))
        assertEquals(beforePause, restored)
        assertEquals(listOf(lake), restored.pendingStops)
        assertEquals(home, restored.destination)
        assertTrue(restored.preferences.avoidHighways)
    }

    @Test fun interruptedSessionIsRecoverableWithoutSkippingStops() {
        val active = TourSession(home, listOf(work, lake), 0,
            RoutePreferences(), 45.001, 9.001, 555L, paused = false)
        val restored = TourSessionStore.decode(TourSessionStore.encode(active))
        assertFalse(restored.paused)
        assertEquals(listOf(work, lake), restored.pendingStops)
    }

    @Test fun finalLegReturnsHomeWithoutRevisitingCompletedStops() {
        val session = TourSession(home, listOf(work), 1,
            RoutePreferences(), 45.15, 9.12, 999L, paused = true)
        val loaded = TourSessionStore.decode(TourSessionStore.encode(session))
        assertTrue(loaded.pendingStops.isEmpty())
        assertEquals("Casa", loaded.destination.name)
    }

    @Test fun rejectsCorruptAndOutOfRangeProgress() {
        val checkpoint = TourSession(home, listOf(work), 0,
            RoutePreferences(), 45.0, 9.0, 1L, paused = true)
        val raw = org.json.JSONObject(TourSessionStore.encode(checkpoint))
        raw.put("completed", 2)
        try {
            TourSessionStore.decode(raw.toString())
            throw AssertionError("Invalid waypoint progress accepted")
        } catch (_: IllegalArgumentException) {}
    }
}
