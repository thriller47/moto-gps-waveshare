package com.motogps.android

import com.motogps.android.viewmodel.OfflinePoiCategorySearch
import com.motogps.android.viewmodel.PhotonPlaceSearch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.URLDecoder

class PhotonOnlinePoiCategoryTest {
    private fun decoded(query: String): String =
        URLDecoder.decode(query, "UTF-8")

    @Test fun trattoriaUsesRestaurantTagNotBusinessName() {
        val kinds = OfflinePoiCategorySearch.categories("trattoria")
        assertEquals(listOf("restaurant"), kinds)
        val url = decoded(PhotonPlaceSearch.categoryEndpoint(kinds, 45.0, 7.0, 15.0))
        assertTrue(url.contains("include=osm.amenity.restaurant,osm.amenity.fast_food"))
        assertTrue(url.contains("&bbox="))
        assertFalse(url.contains("?q="))
        assertFalse(url.contains("trattoria"))
    }

    @Test fun barSearchesCafeAndBarOsmSubtypes() {
        val url = decoded(PhotonPlaceSearch.categoryEndpoint(
            OfflinePoiCategorySearch.categories("bar"), 45.0, 7.0, 15.0))
        assertTrue(url.contains("osm.amenity.bar"))
        assertTrue(url.contains("osm.amenity.pub"))
        assertTrue(url.contains("osm.amenity.cafe"))
    }

    @Test fun hotelsIncludeOtherOsmAccommodationTypes() {
        val url = decoded(PhotonPlaceSearch.categoryEndpoint(
            OfflinePoiCategorySearch.categories("hotel"), 45.0, 7.0, 15.0))
        for (subtype in listOf("hotel", "motel", "guest_house", "hostel",
                "apartment", "chalet")) {
            assertTrue(url.contains("osm.tourism." + subtype))
        }
    }

    @Test fun categoryRadiusIsBounded() {
        val url = decoded(PhotonPlaceSearch.categoryEndpoint(
            listOf("fuel"), 45.0, 7.0, 50.0))
        assertTrue(url.contains("include=osm.amenity.fuel"))
        assertTrue(url.contains("&limit=30"))
        assertFalse(url.contains("&q="))
        assertTrue(url.contains("&lat=45.0&lon=7.0"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun noUnsupportedCategoryAccepted() {
        PhotonPlaceSearch.categoryEndpoint(listOf("trattoria"), 45.0, 7.0, 15.0)
    }
}
