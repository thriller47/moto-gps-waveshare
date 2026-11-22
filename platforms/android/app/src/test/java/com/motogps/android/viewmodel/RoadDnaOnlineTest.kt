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

    @Test fun categoryQueriesFetchBeachesInsteadOfGenericAttractions() {
        val route = listOf(43.0 to 7.5, 43.1 to 7.6)
        val beach = RoadDnaOnline.geoBody(route, "spiagge")
        assertEquals("[\"beach\"]", beach.getJSONArray("categories").toString())
        assertEquals(2000, beach.getJSONObject("filter").getInt("buffer"))
        val url = RoadDnaOnline.tripRadiusUrl(43.0, 7.5, "key",
            categoryId = "spiagge")
        assertTrue(url.contains("kinds=beaches"))
        assertFalse(url.contains("castles"))
        val lowRate = RoadDnaOnline.tripRadiusUrl(43.0, 7.5, "key",
            rate = 1, categoryId = "spiagge")
        assertTrue(lowRate.contains("rate=1"))
        assertTrue(lowRate.contains("kinds=beaches"))
        val standard = RoadDnaOnline.geoBody(route)
        assertTrue(standard.getJSONArray("categories").toString()
            .contains("tourism.sights"))
    }

    @Test fun tourismCategoryFilterRejectsCastlesWhenAskingForBeaches() {
        val beach = RoadDnaOnlinePoi("Spiaggia Grande", "beach", 80,
            "Geoapify", 43.0, 7.0)
        val castle = RoadDnaOnlinePoi("Castello", "tourism.sights.castle", 80,
            "Geoapify", 43.0, 7.0)
        val sand = RoadDnaOnlinePoi("Spiaggia", "interesting_places", 80,
            "OpenTripMap", 43.0, 7.0, "N1",
            categoryTags = listOf("interesting_places", "beaches"))
        assertTrue(RoadDnaCategories.matches("spiagge", beach))
        assertTrue(RoadDnaCategories.matches("spiagge", sand))
        assertFalse(RoadDnaCategories.matches("spiagge", castle))
        assertTrue(RoadDnaCategories.matches("consigliati", castle))
    }

    @Test fun historyAndPanoramaCategoriesUseProviderLabels() {
        val history = RoadDnaOnlinePoi("Forte", "tourism.sights.fort", 100,
            "Geoapify", 45.0, 7.0)
        val panorama = RoadDnaOnlinePoi("Belvedere",
            "tourism.attraction.viewpoint", 100, "Geoapify", 45.0, 7.0)
        assertTrue(RoadDnaCategories.matches("castelli", history))
        assertFalse(RoadDnaCategories.matches("panorami", history))
        assertTrue(RoadDnaCategories.matches("panorami", panorama))
        assertFalse(RoadDnaCategories.matches("castelli", panorama))
        val tourism = RoadDnaCategories.options.map { it.id }
        assertTrue(tourism.containsAll(listOf("consigliati", "spiagge",
            "panorami", "castelli", "cultura", "natura", "terme")))
    }

    @Test fun restaurantHotelAndEntertainmentUseSpecificGeoapifyCategories() {
        val route = listOf(45.10 to 7.10, 45.15 to 7.15)
        val expected = mapOf(
            "ristoranti" to "catering.restaurant",
            "hotel" to "accommodation.hotel",
            "divertimenti" to "entertainment.theme_park"
        )
        for ((id, category) in expected) {
            assertTrue(RoadDnaCategories.options.any { it.id == id })
            val body = RoadDnaOnline.geoBody(route, id)
            assertTrue(body.getJSONArray("categories").toString().contains(category))
            assertFalse(RoadDnaCategories.usesOpenTripMap(id))
        }
        assertTrue(RoadDnaCategories.usesOpenTripMap("consigliati"))
        assertTrue(RoadDnaCategories.usesOpenTripMap("spiagge"))
    }

    @Test fun restaurantHotelAndEntertainmentFilteringRejectsUnrelatedPois() {
        val restaurant = RoadDnaOnlinePoi("Trattoria", "catering.restaurant.italian",
            100, "Geoapify", 45.1, 7.1)
        val hotel = RoadDnaOnlinePoi("Albergo", "accommodation.hotel",
            150, "Geoapify", 45.1, 7.1)
        val cinema = RoadDnaOnlinePoi("Cinema", "entertainment.cinema",
            200, "Geoapify", 45.1, 7.1)
        val plaque = RoadDnaOnlinePoi("Lapide", "tourism.sights.memorial",
            30, "Geoapify", 45.1, 7.1)
        assertTrue(RoadDnaCategories.matches("ristoranti", restaurant))
        assertFalse(RoadDnaCategories.matches("ristoranti", hotel))
        assertFalse(RoadDnaCategories.matches("ristoranti", plaque))
        assertTrue(RoadDnaCategories.matches("hotel", hotel))
        assertFalse(RoadDnaCategories.matches("hotel", restaurant))
        assertTrue(RoadDnaCategories.matches("divertimenti", cinema))
        assertFalse(RoadDnaCategories.matches("divertimenti", plaque))
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

    @Test fun geoapifyRanksSpecificTouringCategoriesNotOnlyFirstGenericCategory() {
        val response = JSONObject().put("features", JSONArray()
            .put(JSONObject().put("geometry", JSONObject()
                .put("coordinates", JSONArray().put(7.5).put(45.1)))
                .put("properties", JSONObject()
                    .put("name", "Forte panoramico")
                    .put("categories", JSONArray()
                        .put("tourism.sights")
                        .put("tourism.sights.fort"))))
            .put(JSONObject().put("geometry", JSONObject()
                .put("coordinates", JSONArray().put(7.51).put(45.11)))
                .put("properties", JSONObject()
                    .put("name", "Targa in piazza")
                    .put("categories", JSONArray()
                        .put("tourism.sights")
                        .put("tourism.sights.memorial.monument")))))
        val pois = RoadDnaOnline.parseGeo(response)
        assertEquals(2, pois.size)
        assertEquals("tourism.sights.fort", pois[0].category)
        assertTrue(RoadDnaOnline.interestScore(pois[0]) >= 90)
        assertEquals(0, RoadDnaOnline.interestScore(pois[1]))
        assertEquals(listOf("Forte panoramico"),
            RoadDnaOnline.recommendedPlaces(pois).map { it.name })
    }

    @Test fun opentripmapUsesAllKindsAndExcludesMinorMonuments() {
        val response = JSONArray().put(JSONObject()
            .put("name", "Castello sulla collina").put("xid", "N123")
            .put("kinds", "interesting_places,castles,historic")
            .put("point", JSONObject().put("lat", 45.1).put("lon", 7.5)))
            .put(JSONObject()
                .put("name", "Piccola statua").put("xid", "N124")
                .put("kinds", "interesting_places,monuments_and_memorials,statues")
                .put("point", JSONObject().put("lat", 45.12).put("lon", 7.52)))
        val pois = RoadDnaOnline.parseTrip(response)
        assertEquals(2, pois.size)
        assertEquals(3, pois[0].categoryTags.size)
        assertTrue(RoadDnaOnline.interestScore(pois[0]) >= 95)
        assertEquals(0, RoadDnaOnline.interestScore(pois[1]))
        assertEquals(listOf("Castello sulla collina"),
            RoadDnaOnline.recommendedPlaces(pois).map { it.name })
    }

    @Test fun touringRankingBeatsProximityWithoutDeletingOtherPlaces() {
        val plaque = RoadDnaOnlinePoi("Lapide", "tourism.sights.memorial", 25,
            "Geoapify", 45.0, 7.0)
        val generic = RoadDnaOnlinePoi("Luogo generico", "tourism.attraction", 10,
            "Geoapify", 45.0, 7.0)
        val lake = RoadDnaOnlinePoi("Lago", "lakes", 900,
            "OpenTripMap", 45.0, 7.0, "L1")
        val ranked = RoadDnaOnline.rankPlaces(listOf(plaque, generic, lake))
        assertEquals("Lago", ranked.first().name)
        assertEquals(3, ranked.size)
        assertEquals(listOf("Lago"), RoadDnaOnline.recommendedPlaces(ranked)
            .map { it.name })
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

    @Test fun geoapifyDetailsUseActualPlaceDataAndMissingDescriptionIsHonest() {
        val response = JSONObject().put("features", JSONArray()
            .put(JSONObject().put("properties", JSONObject()
                .put("feature_type", "details")
                .put("formatted", "Belvedere, Piemonte, Italia")
                .put("website", "https://example.org/belvedere")
                .put("wiki_and_media", JSONObject().put("wikidata", "Q123")))))
        val rendered = RoadDnaOnline.parseGeoDetails(response)
        assertTrue(rendered.contains("Descrizione turistica non disponibile"))
        assertTrue(rendered.contains("Belvedere, Piemonte"))
        assertTrue(rendered.contains("https://example.org/belvedere"))
        assertTrue(rendered.contains("https://www.wikidata.org/wiki/Q123"))
        assertFalse(rendered.contains("undefined"))
        val withDescription = JSONObject().put("features", JSONArray()
            .put(JSONObject().put("properties", JSONObject()
                .put("feature_type", "details")
                .put("description", "Vista panoramica sulla valle."))))
        assertTrue(RoadDnaOnline.parseGeoDetails(withDescription)
            .contains("Vista panoramica sulla valle"))
    }

    @Test fun geoapifyDetailsUseInternationalAndHeritageDescriptions() {
        val international = JSONObject().put("features", JSONArray().put(JSONObject()
            .put("properties", JSONObject()
                .put("feature_type", "details")
                .put("description_international", JSONObject()
                    .put("it", "Antica fortezza piemontese.")
                    .put("en", "Historic fortress.")))))
        assertTrue(RoadDnaOnline.parseGeoDetails(international)
            .contains("Antica fortezza piemontese."))
        val heritage = JSONObject().put("features", JSONArray().put(JSONObject()
            .put("properties", JSONObject().put("feature_type", "details")
                .put("heritage", JSONObject()
                    .put("description", "Monumento tutelato.")))))
        assertTrue(RoadDnaOnline.parseGeoDetails(heritage)
            .contains("Monumento tutelato."))
    }

    @Test fun wikipediaExtractOnlyAppearsWhenRealDescriptionIsMissing() {
        val absent = JSONObject().put("features", JSONArray().put(JSONObject()
            .put("properties", JSONObject()
                .put("feature_type", "details")
                .put("formatted", "Piemonte, Italia"))))
        val shown = RoadDnaOnline.parseGeoDetails(absent,
            "Il castello sorge sulla cima della collina.",
            "https://it.wikipedia.org/wiki/Castello")
        assertTrue(shown.contains("Da Wikipedia (estratto): Il castello"))
        assertTrue(shown.contains("Wikipedia (CC BY-SA 4.0)"))
        assertTrue(shown.contains("https://it.wikipedia.org/wiki/Castello"))
        assertFalse(shown.contains("Descrizione turistica non disponibile"))
        val present = JSONObject().put("features", JSONArray().put(JSONObject()
            .put("properties", JSONObject()
                .put("feature_type", "details")
                .put("description", "Testo verificato dal provider."))))
        val rendered = RoadDnaOnline.parseGeoDetails(present, "Testo Wikipedia")
        assertTrue(rendered.contains("Testo verificato dal provider."))
        assertFalse(rendered.contains("Testo Wikipedia"))
    }

    @Test fun wikipediaSummaryUrlsAcceptOnlyLinkedTrustedHostsAndArticleNames() {
        assertEquals(
            "https://it.wikipedia.org/api/rest_v1/page/summary/Abbazia_di_Staffarda",
            RoadDnaOnline.wikipediaSummaryUrl("it:Abbazia di Staffarda"))
        assertEquals(
            "https://en.wikipedia.org/api/rest_v1/page/summary/Castle_of_Cuneo",
            RoadDnaOnline.wikipediaSummaryUrl(
                "https://en.wikipedia.org/wiki/Castle_of_Cuneo"))
        assertNull(RoadDnaOnline.wikipediaSummaryUrl("https://evil.example/wiki/Castle"))
        assertNull(RoadDnaOnline.wikipediaSummaryUrl("https://it.wikipedia.org.evil/wiki/Castle"))
        assertNull(RoadDnaOnline.wikipediaSummaryUrl("https://it.wikipedia.org/wiki/Castle?x=1"))
        assertNull(RoadDnaOnline.wikipediaSummaryUrl("zz:Unknown"))
        assertNull(RoadDnaOnline.wikipediaSummaryUrl("it:../admin"))
    }

    @Test fun wikidataFallbackUsesExactLinkedItemAndPrefersItalian() {
        val data = JSONObject().put("entities", JSONObject()
            .put("Q123", JSONObject().put("sitelinks", JSONObject()
                .put("enwiki", JSONObject().put("title", "Castle"))
                .put("itwiki", JSONObject().put("title", "Castello")))))
        assertEquals("it:Castello",
            RoadDnaOnline.wikidataWikipediaReference(data, "Q123"))
        assertNull(RoadDnaOnline.wikidataWikipediaReference(data, "Q124"))
    }

    @Test fun wikidataDescriptionWithoutWikipediaArticleIsUsable() {
        val data = JSONObject().put("entities", JSONObject()
            .put("Q123", JSONObject().put("descriptions", JSONObject()
                .put("en", JSONObject().put("language", "en")
                    .put("value", "hilltop castle near the road"))
                .put("it", JSONObject().put("language", "it")
                    .put("value", "castello su un colle")))))
        assertEquals("castello su un colle",
            RoadDnaOnline.wikidataShortDescription(data, "Q123"))
        assertNull(RoadDnaOnline.wikidataShortDescription(data, "Q124"))

        val withoutItalian = JSONObject().put("entities", JSONObject()
            .put("Q123", JSONObject().put("descriptions", JSONObject()
                .put("en", JSONObject().put("value", "historic monument")))))
        assertEquals("historic monument",
            RoadDnaOnline.wikidataShortDescription(withoutItalian, "Q123"))
        assertNull(RoadDnaOnline.wikidataShortDescription(JSONObject(), "Q123"))
    }

    @Test fun wikidataDescriptionIsAttributedAndNotMislabelledAsWikipedia() {
        val data = JSONObject().put("features", JSONArray().put(JSONObject()
            .put("properties", JSONObject()
                .put("feature_type", "details")
                .put("formatted", "Piemonte, Italia")
                .put("wiki_and_media", JSONObject().put("wikidata", "Q123")))))
        val rendered = RoadDnaOnline.parseGeoDetails(
            data, "castello su un colle", "https://www.wikidata.org/wiki/Q123",
            "Wikidata")
        assertTrue(rendered.contains("Da Wikidata (descrizione breve): castello su un colle"))
        assertTrue(rendered.contains("Wikidata (CC0)"))
        assertFalse(rendered.contains("Da Wikipedia (estratto)"))
        assertTrue(rendered.contains("Indirizzo: Piemonte, Italia"))
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
