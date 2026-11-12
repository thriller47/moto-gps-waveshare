package com.motogps.android

import com.motogps.android.viewmodel.OfflinePhotonPlaceSearch
import com.motogps.android.viewmodel.PhotonOfflineRanking
import org.junit.Assert.assertEquals
import org.junit.Test

class OfflinePhotonPlaceSearchTest {
    private fun place(name: String, city: String, kind: String, lat: Double,
                      lon: Double = 7.0) =
        PhotonOfflineRanking.Candidate(name, name, "", city, "", "",
                                      kind, 0.5, lat, lon)

    @Test fun queryIsCaseAccentAndFtsOperatorIndependent() {
        assertEquals("forli*", OfflinePhotonPlaceSearch.tokenize("FORLÌ"))
        assertEquals("via* roma* torino*",
            OfflinePhotonPlaceSearch.tokenize("Via ROMA, Torino"))
        assertEquals("courma*", OfflinePhotonPlaceSearch.tokenize("Courma"))
        assertEquals("roma* or* 1* 1*",
            OfflinePhotonPlaceSearch.tokenize("Roma OR 1=1"))
        assertEquals("", OfflinePhotonPlaceSearch.tokenize("***"))
    }

    @Test fun cityWinsOverStreetsAndHouseWithSameName() {
        val items = listOf(
            place("Via Courmayeur", "Roma", "street", 41.9),
            place("Courmayeur", "Courmayeur", "house", 45.8),
            place("Courmayeur", "Courmayeur", "city", 45.83))
        val hits = PhotonOfflineRanking.rank(items, "COURMAYEUR", null)
        assertEquals("city", hits.first().kind)
    }

    @Test fun mountainPassBeatsStreetAndHouse() {
        val items = listOf(
            place("Via Passo dello Stelvio", "Brescia", "street", 45.5),
            place("Passo Stelvio", "Bormio", "house", 46.5),
            place("Passo Stelvio", "Stilfserjoch", "locality", 46.52))
        val hits = PhotonOfflineRanking.rank(items, "Passo Stelvio", null)
        assertEquals("locality", hits.first().kind)
    }

    @Test fun targetCityWinsAndDuplicateStreetSegmentsCollapse() {
        val items = listOf(
            place("Via Roma", "Torino di Sangro", "street", 42.1),
            place("Via Roma", "Torino", "street", 45.06),
            place("Via Roma", "Torino", "street", 45.07),
            place("Via Roma", "Torino", "street", 45.08))
        val hits = PhotonOfflineRanking.rank(items, "Via Roma Torino", null)
        assertEquals("Torino", hits.first().city)
        assertEquals(2, hits.size)
    }
}
