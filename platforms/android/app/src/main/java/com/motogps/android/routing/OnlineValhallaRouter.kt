package com.motogps.android.routing

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Experimental public Valhalla HTTP fallback. This uses Valhalla's native /route
 * JSON format, not OSRM, and returns the same Moto-GPS BLE route envelope as JNI.
 * Keep only light, user-initiated traffic on the public FOSSGIS demo server.
 */
internal object OnlineValhallaRouter {
    private const val ENDPOINT = "https://valhalla1.openstreetmap.de/route"

    fun request(
        fromLat: Double, fromLon: Double, toLat: Double, toLon: Double,
        rerouting: Boolean = false, gpsBearing: Float? = null
    ): JSONObject {
        require(fromLat in -90.0..90.0 && toLat in -90.0..90.0 &&
            fromLon in -180.0..180.0 && toLon in -180.0..180.0) {
            "Coordinate GPS non valide"
        }
        val origin = JSONObject().put("lat", fromLat).put("lon", fromLon)
            .put("type", "break")
        if (rerouting && gpsBearing != null && gpsBearing.isFinite() &&
            gpsBearing in 0f..359.999f) {
            origin.put("heading", gpsBearing.toDouble())
            origin.put("heading_tolerance", 45)
        }
        return JSONObject()
            .put("locations", JSONArray().put(origin).put(
                JSONObject().put("lat", toLat).put("lon", toLon).put("type", "break")))
            .put("costing", "auto")
            .put("units", "km")
            .put("language", "it-IT")
            .put("shape_format", "polyline6")
    }

    fun route(
        fromLat: Double, fromLon: Double, toLat: Double, toLon: Double,
        rerouting: Boolean = false, gpsBearing: Float? = null
    ): JSONObject {
        val requestJson = request(fromLat, fromLon, toLat, toLon, rerouting, gpsBearing)
        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 25_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Moto-GPS-Android")
            setRequestProperty("X-Client-Id", "moto-gps-waveshare")
        }
        try {
            conn.outputStream.use { it.write(requestJson.toString().toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            if (status !in 200..299) {
                val details = conn.errorStream?.bufferedReader()?.use { it.readText() }
                    ?.take(180)?.replace("\n", " ") ?: ""
                throw IOException("Valhalla online HTTP $status: $details")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return ValhallaRouteAdapter.toMotoRoute(
                JSONObject(body), fromLat, fromLon, toLat, toLon, "valhalla-online")
        } finally {
            conn.disconnect()
        }
    }
}
