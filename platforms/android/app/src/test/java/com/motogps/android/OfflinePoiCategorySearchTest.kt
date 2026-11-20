package com.motogps.android

import com.motogps.android.viewmodel.OfflinePoiCategorySearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflinePoiCategorySearchTest {
    @Test fun foodAliasesMapToRealOsmCategories() {
        assertEquals(listOf("restaurant"),
            OfflinePoiCategorySearch.categories("Trattoria"))
        assertEquals(listOf("restaurant"),
            OfflinePoiCategorySearch.categories("Ristoranti"))
        assertEquals(listOf("bar", "cafe"),
            OfflinePoiCategorySearch.categories("BAR"))
        assertEquals(listOf("cafe", "bar"),
            OfflinePoiCategorySearch.categories("caffè"))
    }

    @Test fun lodgingAliasesShareTheSameCategory() {
        for (query in listOf("albergo", "hotel", "motel", "affittacamere", "ostello")) {
            assertEquals(query, listOf("hotel"),
                OfflinePoiCategorySearch.categories(query))
        }
    }

    @Test fun fuelAliasesAndOrdinaryNameSearchRemainSeparate() {
        for (query in listOf(
                "distributore", "distributori", "benzinaio", "benzinai",
                "distributore benzina", "distributori benzina",
                "distributore carburante", "distributori carburante",
                "stazione di servizio", "stazioni di servizio")) {
            assertEquals(query, listOf("fuel"),
                OfflinePoiCategorySearch.categories(query))
        }
        assertTrue(OfflinePoiCategorySearch.categories("Eni").isEmpty())
        assertTrue(OfflinePoiCategorySearch.categories("La Pergola").isEmpty())
        assertTrue(OfflinePoiCategorySearch.categories("Via Roma").isEmpty())
        assertTrue(OfflinePoiCategorySearch.categories("Hotel Torino").isEmpty())
    }
}
