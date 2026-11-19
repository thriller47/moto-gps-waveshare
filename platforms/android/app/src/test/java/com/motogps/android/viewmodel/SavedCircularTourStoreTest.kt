package com.motogps.android.viewmodel

import com.motogps.android.routing.RoutePreferences
import com.motogps.android.routing.RouteStyle
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedCircularTourStoreTest {
    private val mandatory = listOf(
        PlaceSearchResult("Passo", "", "Montagna", 45.1, 9.1),
        PlaceSearchResult("Lago", "", "Valle", 45.2, 9.2)
    )

    @Test fun roundTripPreservesOrderAndRoutePreferences() {
        val tour = SavedCircularTour(
            "tour-1", "Giro dei laghi", 45.0 to 9.0, 160, 90,
            mandatory, mandatory, RoutePreferences(
                style = RouteStyle.PANORAMIC, avoidHighways = true, curveLevel = 2)
        )
        val restored = SavedCircularTourStore.decode(
            SavedCircularTourStore.encode(listOf(tour)))
        assertEquals(listOf(tour), restored)
        assertEquals("Passo", restored.single().mandatory.first().name)
        assertEquals("Lago", restored.single().via.last().name)
    }

    @Test fun invalidRecordsAreIgnoredButOtherToursSurvive() {
        val tour = SavedCircularTour("ok", "Anello", 45.0 to 9.0,
            100, 0, emptyList(), emptyList(), RoutePreferences())
        val json = JSONArray(SavedCircularTourStore.encode(listOf(tour)))
        json.put(JSONObject().put("version", 999))
        val restored = SavedCircularTourStore.decode(json.toString())
        assertEquals(1, restored.size)
        assertEquals("ok", restored.single().id)
    }

    @Test fun preferencesAndAllStopsRemainWithinLimits() {
        val tour = SavedCircularTour("ok", "Tappe", 44.0 to 8.0,
            300, 270, mandatory, mandatory,
            RoutePreferences(style = RouteStyle.TOURING, avoidTolls = true,
                avoidFerries = true, curveLevel = 3))
        val restored = SavedCircularTourStore.decode(
            SavedCircularTourStore.encode(listOf(tour))).single()
        assertTrue(restored.preferences.avoidTolls)
        assertTrue(restored.preferences.avoidFerries)
        assertEquals(3, restored.preferences.curveLevel)
    }
}
