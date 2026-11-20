package com.motogps.android

import com.motogps.android.viewmodel.FuelStationSearch
import com.motogps.android.viewmodel.PhotonOfflineRanking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FuelStationSearchTest {
    @Test fun genericFuelQueriesDoNotOverrideBrandOrStreetSearch() {
        for (query in listOf("distributore", "distributori", "benzinaio",
                "benzinaio", "stazione di servizio", "distributore benzina")) {
            assertTrue(query, FuelStationSearch.isGenericQuery(query))
        }
        for (query in listOf("Eni", "Q8", "Via Eni", "Via Roma", "distributore Torino")) {
            assertFalse(query, FuelStationSearch.isGenericQuery(query))
        }
    }

    @Test fun brandedFuelNamesAreAcceptedWithoutConfusingAddresses() {
        for (brand in listOf("Eni", "Agip", "IP", "Q8", "Tamoil",
                "Esso", "Shell", "Beyfin", "TotalEnergies", "Vega")) {
            assertTrue(brand, FuelStationSearch.isStation(brand, "other"))
        }
        assertTrue(FuelStationSearch.isStation("Q8 Easy", "other"))
        assertTrue(FuelStationSearch.isStation("Eni Live Station", "other"))
        assertTrue(FuelStationSearch.isStation("IP Carburanti", "other"))
        assertTrue(FuelStationSearch.isStation("Stazione carburante", "fuel"))
        assertFalse(FuelStationSearch.isStation("Via Eni", "street"))
        assertFalse(FuelStationSearch.isStation("Piazza IP", "street"))
        assertFalse(FuelStationSearch.isStation("Ipotesi", "other"))
        assertFalse(FuelStationSearch.isStation("Tamoil Torino", "city"))
    }

    @Test fun differentBrandsShareOneProximityRankingAndDoNotDeduplicate() {
        fun station(name: String, lon: Double) = PhotonOfflineRanking.Candidate(
            name, "", "", "Cuneo", "Piemonte", "", "fuel",
            0.0, 44.4, lon)
        val candidates = listOf(station("Eni", 7.8), station("Q8", 7.61),
            station("IP", 7.65), station("Tamoil", 7.61))
        val ranked = PhotonOfflineRanking.rank(
            candidates, "distributore", 44.4 to 7.6)
        assertEquals(listOf("Q8", "Tamoil", "IP", "Eni"),
            ranked.map { it.name })
    }

    @Test fun fuelBrandsAreCombinedInOneIndexedQuery() {
        assertTrue(FuelStationSearch.ftsQuery.contains("eni"))
        assertTrue(FuelStationSearch.ftsQuery.contains("ip"))
        assertTrue(FuelStationSearch.ftsQuery.contains("q8"))
        assertTrue(FuelStationSearch.ftsQuery.contains(" OR "))
    }
}
