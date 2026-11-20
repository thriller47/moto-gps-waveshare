package com.motogps.android.viewmodel

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * A single Photon search is sent when the user presses Search. There is no
 * continuous autocomplete or Render/OSRM dependency.
 */
internal object PhotonPlaceSearch {
    private const val ENDPOINT = "https://photon.komoot.io/api/"

    fun endpoint(query: String, gpsLocation: Pair<Double, Double>?): String {
        val parameters = StringBuilder("?q=")
            .append(URLEncoder.encode(query.trim(), "UTF-8"))
            // The public photon.komoot.io instance does not guarantee Italian
            // as a response language and rejects unsupported values with HTTP 400.
            // Without lang Photon uses its default/local OSM place names.
            .append("&limit=30")
        if (gpsLocation != null) {
            val (lat, lon) = gpsLocation
            if (lat.isFinite() && lon.isFinite() &&
                lat in -90.0..90.0 && lon in -180.0..180.0) {
                parameters.append("&lat=").append(lat)
                    .append("&lon=").append(lon)
            }
        }
        return ENDPOINT + parameters
    }

    fun parse(response: JSONObject): List<PlaceSearchResult> {
        val items = response.optJSONArray("features") ?: return emptyList()
        val output = mutableListOf<PlaceSearchResult>()
        val visited = HashSet<String>()
        for (i in 0 until items.length()) {
            val feature = items.optJSONObject(i) ?: continue
            val geometry = feature.optJSONObject("geometry") ?: continue
            if (geometry.optString("type") != "Point") continue
            val coords = geometry.optJSONArray("coordinates") ?: continue
            if (coords.length() < 2) continue
            val lon = coords.optDouble(0, Double.NaN)
            val lat = coords.optDouble(1, Double.NaN)
            if (!lat.isFinite() || !lon.isFinite() ||
                lat !in -90.0..90.0 || lon !in -180.0..180.0) continue
            val props = feature.optJSONObject("properties") ?: JSONObject()
            fun field(name: String) = props.optString(name, "").trim()
                .takeIf { it.isNotBlank() } ?: ""
            val road = listOf(field("street"), field("housenumber"))
                .filter { it.isNotBlank() }.joinToString(" ")
            val locality = listOf(field("postcode"), field("city").ifBlank {
                field("town").ifBlank { field("village") }
            }).filter { it.isNotBlank() }.joinToString(" ")
            val name = field("name").ifBlank { road.ifBlank { locality } }
            if (name.isBlank()) continue
            val area = listOf(locality, field("state"), field("country"))
                .filter { it.isNotBlank() }.distinct().joinToString(", ")
            val address = listOf(road, locality).filter { it.isNotBlank() }
                .distinct().joinToString(", ")
            val key = "${lat},${lon},${name}"
            if (visited.add(key)) output.add(PlaceSearchResult(name, address, area, lat, lon))
        }
        return output
    }

    /** Re-rank the larger online candidate set with the same geographical
     * policy used by offline Photon. No GPS keeps Photon relevance as supplied. */
    fun rank(response: JSONObject, query: String,
             gpsLocation: Pair<Double, Double>?): List<PlaceSearchResult> {
        val parsed = parse(response)
        if (parsed.isEmpty()) return emptyList()
        val features = response.optJSONArray("features")
        val mapped = parsed.map { place ->
            var city = ""
            var street = ""
            var kind = "other"
            if (features != null) {
                for (i in 0 until features.length()) {
                    val feature = features.optJSONObject(i) ?: continue
                    val coords = feature.optJSONObject("geometry")?.optJSONArray("coordinates")
                        ?: continue
                    if (coords.length() < 2 ||
                        coords.optDouble(0, Double.NaN) != place.longitude ||
                        coords.optDouble(1, Double.NaN) != place.latitude) continue
                    val props = feature.optJSONObject("properties") ?: continue
                    city = listOf("city", "town", "village").map {
                        props.optString(it, "").trim()
                    }.firstOrNull { it.isNotBlank() } ?: ""
                    street = props.optString("street", "").trim()
                    val osmValue = props.optString("osm_value", "")
                    kind = when {
                        props.optString("type") == "street" || osmValue == "residential" ||
                            osmValue == "pedestrian" || osmValue == "tertiary" ||
                            osmValue == "secondary" -> "street"
                        props.optString("housenumber", "").isNotBlank() -> "house"
                        props.optString("type") in listOf("city", "town", "village") -> "city"
                        else -> "other"
                    }
                    break
                }
            }
            PhotonOfflineRanking.Candidate(place.name, street, "", city, "", "",
                kind, 0.0, place.latitude, place.longitude) to place
        }
        // With no valid fix we should preserve Photon’s original relevance
        // rather than ranking unknown places by arbitrary geography.
        val validGps = gpsLocation?.takeIf {
            it.first.isFinite() && it.second.isFinite() &&
                it.first in -90.0..90.0 && it.second in -180.0..180.0
        }
        if (validGps == null) return parsed.take(8)
        val ranked = PhotonOfflineRanking.rank(mapped.map { it.first }, query, validGps, 8)
        return ranked.mapNotNull { candidate ->
            mapped.firstOrNull { it.first === candidate }?.second
        }
    }


    /**
     * Category-only nearby Photon search. Photon allows q to be absent when
     * include= filters are supplied; do not search the Italian category word
     * in a POI name. Bound the server query and filter its OSM tags again.
     * One request at a time, only on the user's explicit Search action.
     */
    private val poiTags = mapOf(
        "fuel" to listOf("amenity" to "fuel"),
        "restaurant" to listOf("amenity" to "restaurant", "amenity" to "fast_food"),
        "cafe" to listOf("amenity" to "cafe"),
        "bar" to listOf("amenity" to "bar", "amenity" to "pub"),
        "hotel" to listOf(
            "tourism" to "hotel", "tourism" to "motel",
            "tourism" to "guest_house", "tourism" to "hostel",
            "tourism" to "apartment", "tourism" to "chalet")
    )

    fun categoryEndpoint(categories: List<String>, latitude: Double,
                         longitude: Double, radiusKm: Double): String {
        require(categories.isNotEmpty() && categories.all { it in poiTags })
        require(latitude.isFinite() && latitude in -85.0..85.0 &&
            longitude.isFinite() && longitude in -180.0..180.0)
        require(radiusKm in 1.0..150.0)
        val tags = categories.flatMap { poiTags.getValue(it) }.distinct()
        val latDelta = radiusKm / 111.2
        val lonDelta = radiusKm / (111.2 *
            kotlin.math.cos(Math.toRadians(latitude)).coerceAtLeast(0.05))
        val minLon = (longitude - lonDelta).coerceAtLeast(-180.0)
        val maxLon = (longitude + lonDelta).coerceAtMost(180.0)
        val bbox = listOf(minLon, (latitude - latDelta).coerceAtLeast(-90.0),
            maxLon, (latitude + latDelta).coerceAtMost(90.0)).joinToString(",")
        return ENDPOINT + "?include=" + URLEncoder.encode(
            tags.joinToString(",") { "osm.${it.first}.${it.second}" }, "UTF-8") +
            "&bbox=" + URLEncoder.encode(bbox, "UTF-8") +
            "&lat=" + latitude + "&lon=" + longitude +
            "&location_bias_scale=0&limit=30"
    }

    fun categoryResults(response: JSONObject, categories: List<String>,
                        latitude: Double, longitude: Double,
                        radiusKm: Double): List<PlaceSearchResult> {
        val allowed = categories.flatMap { poiTags[it].orEmpty() }.toSet()
        val features = response.optJSONArray("features") ?: return emptyList()
        val matches = ArrayList<PlaceSearchResult>()
        for (i in 0 until features.length()) {
            val feature = features.optJSONObject(i) ?: continue
            val props = feature.optJSONObject("properties") ?: continue
            if ((props.optString("osm_key") to props.optString("osm_value")) !in allowed)
                continue
            val point = feature.optJSONObject("geometry")?.optJSONArray("coordinates")
                ?: continue
            if (point.length() < 2) continue
            val lon = point.optDouble(0, Double.NaN)
            val lat = point.optDouble(1, Double.NaN)
            if (!lat.isFinite() || !lon.isFinite()) continue
            val radLat = Math.toRadians(lat - latitude)
            val radLon = Math.toRadians(lon - longitude)
            val h = kotlin.math.sin(radLat / 2).let { it * it } +
                kotlin.math.cos(Math.toRadians(latitude)) *
                kotlin.math.cos(Math.toRadians(lat)) *
                kotlin.math.sin(radLon / 2).let { it * it }
            val meters = 6371000.0 * 2.0 * kotlin.math.atan2(
                kotlin.math.sqrt(h.coerceIn(0.0, 1.0)),
                kotlin.math.sqrt((1.0 - h).coerceAtLeast(0.0)))
            if (meters > radiusKm * 1000.0) continue
            val name = props.optString("name").trim().ifBlank {
                props.optString("osm_value").replace('_', ' ')
            }
            val city = listOf("city", "town", "village").map {
                props.optString(it).trim()
            }.firstOrNull { it.isNotBlank() }.orEmpty()
            val address = listOf(props.optString("street").trim(), city)
                .filter { it.isNotBlank() }.joinToString(", ")
            matches.add(PlaceSearchResult(name, address,
                props.optString("osm_value"), lat, lon, distanceMeters = meters))
        }
        return matches.distinctBy {
            "${it.latitude},${it.longitude},${it.name}"
        }.sortedBy { it.distanceMeters }.take(8)
    }

    fun searchCategories(categories: List<String>,
                         gpsLocation: Pair<Double, Double>?): List<PlaceSearchResult> {
        if (categories.isEmpty()) return emptyList()
        val fix = gpsLocation?.takeIf {
            it.first.isFinite() && it.second.isFinite() &&
                it.first in -85.0..85.0 && it.second in -180.0..180.0
        } ?: return emptyList()
        val collected = LinkedHashMap<String, PlaceSearchResult>()
        for (radius in listOf(15.0, 50.0, 150.0)) {
            val conn = (URL(categoryEndpoint(
                categories, fix.first, fix.second, radius
            )).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 12_000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "Moto-GPS-Android")
            }
            val results = try {
                if (conn.responseCode !in 200..299)
                    throw IOException("Photon category HTTP ${conn.responseCode}")
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                categoryResults(json, categories, fix.first, fix.second, radius)
            } catch (e: IOException) {
                if (collected.isEmpty()) throw e
                // Preserve close validated matches if a wider request fails.
                break
            } finally {
                conn.disconnect()
            }
            results.forEach { place ->
                collected.putIfAbsent(
                    "${place.latitude},${place.longitude},${place.name}", place)
            }
            if (collected.size >= 8) break
        }
        return collected.values.sortedBy { it.distanceMeters }.take(8)
    }

    fun search(query: String, gpsLocation: Pair<Double, Double>?): List<PlaceSearchResult> {
        require(query.isNotBlank()) { "Inserisci una destinazione" }
        val conn = (URL(endpoint(query, gpsLocation)).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Moto-GPS-Android")
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) {
                val responseMessage = conn.errorStream?.bufferedReader()?.use {
                    it.readText().take(400)
                }?.let { body ->
                    runCatching { JSONObject(body).optString("message").trim() }
                        .getOrNull()?.takeIf { it.isNotBlank() }
                        ?: body.replace('\n', ' ').replace('\r', ' ').trim()
                }?.take(180)
                val message = if (responseMessage.isNullOrBlank()) "Photon HTTP $code"
                    else "Photon HTTP $code: $responseMessage"
                throw IOException(message)
            }
            val response = conn.inputStream.bufferedReader().use { it.readText() }
            rank(JSONObject(response), query, gpsLocation)
        } finally {
            conn.disconnect()
        }
    }
}
