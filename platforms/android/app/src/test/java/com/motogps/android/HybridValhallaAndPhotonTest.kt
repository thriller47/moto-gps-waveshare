package com.motogps.android

import com.motogps.android.routing.OnlineValhallaRouter
import com.motogps.android.viewmodel.PhotonPlaceSearch
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HybridValhallaAndPhotonTest {
    @Test fun onlineRequestUsesNativeValhallaJsonAndGpsBearingOnlyForReroute() {
        val normal = OnlineValhallaRouter.request(45.62, 8.85, 46.53, 10.45)
        assertEquals("auto", normal.getString("costing"))
        assertEquals("it-IT", normal.getString("language"))
        assertEquals("km", normal.getString("units"))
        assertEquals("polyline6", normal.getString("shape_format"))
        assertFalse(normal.getJSONArray("locations").getJSONObject(0).has("heading"))
        val reroute = OnlineValhallaRouter.request(45.62, 8.85, 46.53, 10.45, true, 150f)
        assertEquals(150.0, reroute.getJSONArray("locations")
            .getJSONObject(0).getDouble("heading"), 0.001)
        val stationary = OnlineValhallaRouter.request(45.62, 8.85, 46.53, 10.45, true, Float.NaN)
        assertFalse(stationary.getJSONArray("locations").getJSONObject(0).has("heading"))
    }

    @Test fun photonSearchBiasAndEncodingAreCorrect() {
        val link = PhotonPlaceSearch.endpoint("passo dello stelvio", 45.62 to 8.85)
        assertTrue(link.startsWith("https://photon.komoot.io/api/?q=passo+dello+stelvio"))
        assertTrue(link.contains("limit=8&lang=it"))
        assertTrue(link.contains("&lat=45.62&lon=8.85"))
        assertFalse(PhotonPlaceSearch.endpoint("Roma", null).contains("&lat="))
    }

    @Test fun photonGeoJsonCoordinatesAreLongitudeLatitudeAndInvalidFeaturesSkipped() {
        val good = JSONObject()
            .put("type", "Feature")
            .put("geometry", JSONObject()
                .put("type", "Point")
                .put("coordinates", JSONArray().put(8.85).put(45.62)))
            .put("properties", JSONObject()
                .put("name", "Piazza")
                .put("street", "Via Roma")
                .put("housenumber", "3")
                .put("city", "Gallarate")
                .put("country", "Italia"))
        val bad = JSONObject()
            .put("geometry", JSONObject().put("type", "Point")
                .put("coordinates", JSONArray().put(200.0).put(45.0)))
            .put("properties", JSONObject().put("name", "Bad"))
        val response = JSONObject().put("features", JSONArray().put(good).put(bad))
        val places = PhotonPlaceSearch.parse(response)
        assertEquals(1, places.size)
        assertEquals("Piazza", places[0].name)
        assertEquals(45.62, places[0].latitude, 0.000001)
        assertEquals(8.85, places[0].longitude, 0.000001)
        assertTrue(places[0].address.contains("Via Roma 3"))
        assertTrue(places[0].displayArea.contains("Gallarate"))
    }
}
