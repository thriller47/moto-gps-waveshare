package com.motogps.android

import com.motogps.android.routing.OnlineValhallaRouter
import com.motogps.android.viewmodel.PhotonPlaceSearch
import com.motogps.android.viewmodel.PlaceSearchResult
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
        // The Photon demo does not accept forced Italian response language.
        // Queries in Italian must work without sending lang=it.
        assertTrue(link.contains("limit=30"))
        assertFalse(link.contains("lang="))
        assertTrue(link.contains("&lat=45.62&lon=8.85"))
        assertFalse(PhotonPlaceSearch.endpoint("Roma", null).contains("&lat="))
        val roman = PhotonPlaceSearch.endpoint("San Giovanni d'Asso", null)
        assertTrue(roman.contains("q=San+Giovanni+d%27Asso"))
        assertFalse(roman.contains("lang="))
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
    @Test fun onlineRankingPrefersNearbyViaRomaWhileKeepingExplicitCity() {
        fun street(city: String, lat: Double, lon: Double): JSONObject =
            JSONObject().put("type", "Feature")
                .put("geometry", JSONObject().put("type", "Point")
                    .put("coordinates", JSONArray().put(lon).put(lat)))
                .put("properties", JSONObject()
                    .put("name", "Via Roma")
                    .put("street", "Via Roma")
                    .put("city", city)
                    .put("type", "street"))
        val response = JSONObject().put("features", JSONArray()
            .put(street("Roma", 41.902, 12.496))
            .put(street("Torino", 45.070, 7.680))
            .put(street("Gallarate", 45.660, 8.770)))
        val near = PhotonPlaceSearch.rank(response, "Via Roma", 45.66 to 8.77)
        assertEquals(listOf("Gallarate", "Torino", "Roma"),
            near.map { it.displayArea.substringBefore(",").trim() })
        val remote = PhotonPlaceSearch.rank(response, "Via Roma Torino", 45.66 to 8.77)
        assertTrue(remote.first().displayArea.contains("Torino"))
        // Without a GPS fix preserve server relevance ordering.
        val noGps = PhotonPlaceSearch.rank(response, "Via Roma", null)
        assertTrue(noGps.first().displayArea.contains("Roma"))
    }


    @Test fun internationalQueryDoesNotTrustPartialItalianMatches() {
        val italy = PlaceSearchResult("Via Parigi","Via Parigi, Torino","Torino, Italia",45.0,7.0)
        val france = PlaceSearchResult("Paris","","Île-de-France, France",48.8566,2.3522)
        val local = PlaceSearchResult("Torino","","Piemonte, Italia",45.07,7.68)
        val policy = com.motogps.android.viewmodel.HybridPlaceSearch
        assertFalse(policy.confident("Paris, France",italy))
        assertTrue(policy.confident("Paris, France",france))
        assertTrue(policy.confident("Torino",local))
        assertFalse(policy.confident("Parigi",italy))
        assertEquals(france,policy.merge("Paris, France",listOf(italy),listOf(france)).first())
        assertEquals(listOf(italy),policy.merge("Paris, France",listOf(italy),emptyList()))
        val weakLocal = (0..7).map { italy.copy(latitude=45.0+it*.01) }
        assertTrue(policy.merge("Parigi",weakLocal,listOf(france)).contains(france))
        assertFalse(PhotonPlaceSearch.endpoint("Paris, France",45.0 to 7.0).contains("&lat="))
    }

    @Test fun namedSettlementOrAddressPrecedesAdministrativeNamesake() {
        fun feature(name: String, lat: Double, lon: Double, key: String, value: String,
                    type: String, street: String = "", house: String = "", country: String = "FR") =
            JSONObject().put("geometry",JSONObject().put("type","Point")
                .put("coordinates",JSONArray().put(lon).put(lat)))
                .put("properties",JSONObject().put("name",name).put("osm_key",key)
                    .put("osm_value",value).put("type",type).put("street",street)
                    .put("housenumber",house).put("countrycode",country).put("state","Region"))
        val area = feature("Barcelonnette",44.45,6.8,"boundary","administrative","city")
        val address = feature("Barcelonnette",44.39,6.65,"amenity","townhall","other","Rue du Centre","3")
        val settlement = feature("Barcelonnette",44.39,6.66,"place","town","city")
        fun rank(vararg features: JSONObject) = PhotonPlaceSearch.rank(
            JSONObject().put("features",JSONArray(features.toList())),
            "Barcelonnette",44.45 to 7.8)
        val pair = rank(area,address)
        assertEquals(2,pair.size)
        assertEquals("Indirizzo",pair.first().photonKind)
        assertEquals("Area amministrativa",pair.last().photonKind)
        assertEquals("Centro abitato",rank(area,address,settlement).first().photonKind)
        val different = PhotonPlaceSearch.parse(JSONObject().put("features",JSONArray()
            .put(area).put(feature("Barcelonnette",44.39,6.65,"place","town","city",country="IT"))))
        assertEquals(different,com.motogps.android.viewmodel.PhotonDestinationRanking.preferNavigableNamesakes(different))
    }

    @Test fun searchDistanceLabelsUseMetersAndKilometers() {
        val near = PlaceSearchResult("Via Roma", "", "Italia", 45.0, 8.0, 350.0)
        val far = near.copy(distanceMeters = 1850.0)
        assertEquals("350 m", near.distanceLabel)
        assertEquals("1,9 km", far.distanceLabel)
        assertEquals("", near.copy(distanceMeters = null).distanceLabel)
    }

}
