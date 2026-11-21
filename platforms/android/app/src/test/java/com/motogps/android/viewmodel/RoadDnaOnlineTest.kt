package com.motogps.android.viewmodel

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RoadDnaOnlineTest {
    @Test fun geoapifyCorridorUsesLonLatOrderAndExactBuffer() {
        val body = RoadDnaOnline.geoBody(listOf(
            45.15 to 7.15,
            45.2 to 7.2,
            45.3 to 7.3
        ))
        assertEquals("polyline", body.getJSONObject("filter").getString("type"))
        assertEquals(2000, body.getJSONObject("filter").getInt("buffer"))
        assertEquals("LineString", body.getJSONObject("filter")
            .getJSONObject("geometry").getString("type"))
        val point = body.getJSONObject("filter").getJSONObject("geometry")
            .getJSONArray("coordinates").getJSONArray(0)
        assertEquals(7.15, point.getDouble(0), 0.000001)
        assertEquals(45.15, point.getDouble(1), 0.000001)
        assertEquals(100, body.getInt("limit"))
        assertTrue(body.getJSONArray("categories").toString()
            .contains("tourism.sights"))
        assertFalse(body.toString().contains("apiKey"))
    }

    @Test fun geoapifyRequiresActualRouteNotSinglePlace() {
        try {
            RoadDnaOnline.geoBody(listOf(45.0 to 7.0))
            fail("Expected error for one point")
        } catch (_: IllegalArgumentException) { }
        try {
            RoadDnaOnline.geoBody(listOf(45.0 to 7.0, 91.0 to 7.2))
            fail("Expected error for invalid latitude")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun geoapifyResponseSkipsInvalidPositionsAndUnnamedItems() {
        val source = JSONObject().put("features", JSONArray()
            .put(JSONObject()
                .put("geometry", JSONObject().put("coordinates", JSONArray()
                    .put(7.15).put(45.15)))
                .put("properties", JSONObject()
                    .put("name", "Belvedere")
                    .put("place_id", "abcdef012345")
                    .put("categories", JSONArray().put("tourism.attraction.viewpoint"))))
            .put(JSONObject()
                .put("geometry", JSONObject().put("coordinates", JSONArray()
                    .put(190.0).put(95.0)))
                .put("properties", JSONObject().put("name", "Invalid"))))
        val found = RoadDnaOnline.parseGeo(source)
        assertEquals(1, found.size)
        assertEquals("Belvedere", found[0].name)
        assertEquals("Geoapify", found[0].source)
        assertEquals("abcdef012345", found[0].geoPlaceId)
        assertEquals(45.15, found[0].latitude, 0.000001)
    }

    @Test fun openTripMapListRetainsXidForExplicitDetailsOnly() {
        val input = JSONArray().put(JSONObject()
            .put("xid", "W123456")
            .put("name", "Museo")
            .put("kinds", "museums,cultural")
            .put("point", JSONObject().put("lat", 45.0).put("lon", 7.1)))
            .put(JSONObject()
                .put("xid", "W789")
                .put("name", "")
                .put("point", JSONObject().put("lat", 45.0).put("lon", 7.1)))
        val found = RoadDnaOnline.parseTrip(input)
        assertEquals(1, found.size)
        assertEquals("OpenTripMap", found[0].source)
        assertEquals("W123456", found[0].xid)
    }

    @Test fun openTripMapSamplesByGeographicDistanceNotVertexCount() {
        // Hundreds of vertices packed into the first few metres of a 10 km
        // route must not use up the provider's five radius probes.
        val route = (0..100).map { 45.0 to (7.0 + it * 0.0000001) } +
            listOf(45.0 to 7.03, 45.0 to 7.06, 45.0 to 7.09, 45.0 to 7.12)
        val indices = RoadDnaOnline.tripSampleIndices(route, 5)
        assertEquals(5, indices.size)
        assertEquals(0, indices.first())
        assertEquals(route.lastIndex, indices.last())
        assertTrue(indices[2] >= 101)
    }

    @Test fun geoapifyEnrichmentNeedsSameNameAndNearbyLocation() {
        val original = RoadDnaOnlinePoi("Cittadèlla Storica",
            "tourism.sights.castle", 10, "Geoapify", 45.0, 7.0)
        val wrongPlace = RoadDnaOnlinePoi("Cittadella Storica", "castles", 0,
            "OpenTripMap", 46.0, 7.0, "W1")
        val wrongName = RoadDnaOnlinePoi("Cittadella Nuova", "castles", 0,
            "OpenTripMap", 45.0, 7.0, "W2")
        assertNull(RoadDnaOnline.matchOpenTripMap(original,
            listOf(wrongPlace, wrongName)))
        val matched = RoadDnaOnlinePoi("Cittadella Storica", "castles", 0,
            "OpenTripMap", 45.0002, 7.0001, "W3")
        assertEquals("W3", RoadDnaOnline.matchOpenTripMap(original,
            listOf(wrongPlace, wrongName, matched))?.xid)
    }

    @Test fun geoapifyDetailsUseActualPlaceDataAndMissingDescriptionIsHonest() {
        val response = JSONObject().put("features", JSONArray()
            .put(JSONObject().put("properties", JSONObject()
                .put("feature_type", "details")
                .put("formatted", "Belvedere, Piemonte, Italia")
                .put("website", "https://example.org/belvedere")
                .put("wiki_and_media", JSONObject().put("wikidata", "Q123")))))
        val rendered = RoadDnaOnline.parseGeoDetails(response)
        assertTrue(rendered.contains("Descrizione non disponibile"))
        assertTrue(rendered.contains("Belvedere, Piemonte"))
        assertTrue(rendered.contains("https://example.org/belvedere"))
        assertTrue(rendered.contains("Wikidata: Q123"))
        assertFalse(rendered.contains("undefined"))
        val withDescription = JSONObject().put("features", JSONArray()
            .put(JSONObject().put("properties", JSONObject()
                .put("feature_type", "details")
                .put("description", "Vista panoramica sulla valle."))))
        assertTrue(RoadDnaOnline.parseGeoDetails(withDescription)
            .contains("Vista panoramica sulla valle"))
    }

    @Test fun openTripMapDetailsDoNotPretendRawTagsAreADescription() {
        val response = JSONObject()
            .put("name", "Rocca")
            .put("kinds", "natural,rocks,interesting_places")
            .put("otm", "https://opentripmap.com/en/card/N123")
        val rendered = RoadDnaOnline.parseTripDetails(response)
        assertTrue(rendered.contains("Descrizione non disponibile"))
        assertTrue(rendered.contains("https://opentripmap.com/en/card/N123"))
        assertFalse(rendered.contains("natural,rocks"))
        val described = JSONObject().put("wikipedia_extracts",
            JSONObject().put("text", "Rocca di origine medievale."))
        assertTrue(RoadDnaOnline.parseTripDetails(described)
            .contains("Rocca di origine medievale."))
    }

    @Test fun openTripMapApiKeyIsEncodedForQuery() {
        val url = RoadDnaOnline.tripRadiusUrl(45.0, 7.0, "sample key&test")
        assertTrue(url.startsWith("https://api.opentripmap.com/0.1/en/places/radius?"))
        assertTrue(url.contains("radius=2000"))
        assertTrue(url.contains("limit=25"))
        assertTrue(url.contains("apikey=sample+key%26test"))
        assertFalse(url.contains("apikey=sample key&test"))
    }
}
