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
            .append("&limit=8&lang=it")
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
            if (code !in 200..299) throw IOException("Photon HTTP $code")
            val response = conn.inputStream.bufferedReader().use { it.readText() }
            parse(JSONObject(response))
        } finally {
            conn.disconnect()
        }
    }
}
