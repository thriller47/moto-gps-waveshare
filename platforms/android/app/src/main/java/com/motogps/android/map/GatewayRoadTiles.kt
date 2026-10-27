package com.motogps.android.map

import org.json.JSONObject
import java.io.BufferedWriter
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * Real OpenStreetMap road geometry from the project's existing map gateway.
 * Only provider-supplied coordinates; no synthesized streets or bulk queries.
 */
internal object GatewayRoadTiles {
    const val ZOOM = 15
    private const val GRID = 1 shl ZOOM

    data class Tile(val x: Int, val y: Int) {
        val key: String get() = "${ZOOM}_${x}_${y}"
    }

    fun tileFor(latitude: Double, longitude: Double): Tile {
        require(latitude.isFinite() && longitude.isFinite())
        val lat = latitude.coerceIn(-85.0, 85.0)
        val lon = longitude.coerceIn(-180.0, 180.0)
        val latRad = Math.toRadians(lat)
        val x = floor((lon + 180.0) / 360.0 * GRID).toInt().coerceIn(0, GRID - 1)
        val y = floor((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) * GRID / 2.0)
            .toInt().coerceIn(0, GRID - 1)
        return Tile(x, y)
    }

    fun around(latitude: Double, longitude: Double, radius: Int): List<Tile> {
        val center = tileFor(latitude, longitude)
        val result = mutableListOf<Tile>()
        for (dy in -radius..radius) for (dx in -radius..radius) {
            val x = center.x + dx
            val y = center.y + dy
            if (x in 0 until GRID && y in 0 until GRID) result.add(Tile(x, y))
        }
        return result.sortedWith(
            compareBy<Tile> { kotlin.math.abs(it.x - center.x) + kotlin.math.abs(it.y - center.y) }
                .thenBy { it.y }.thenBy { it.x }
        )
    }

    /**
     * Overpass QL is used only for small, explicit OSM bbox requests.
     * Coordinates originate exclusively from the server's real OSM ways.
     * No Render endpoint is ever consulted.
     */
    fun query(tile: Tile): String {
        val west = tile.x.toDouble() / GRID * 360.0 - 180.0
        val east = (tile.x + 1).toDouble() / GRID * 360.0 - 180.0
        fun latitude(y: Int): Double = Math.toDegrees(
            kotlin.math.atan(kotlin.math.sinh(PI * (1 - 2.0 * y / GRID))))
        val north = latitude(tile.y)
        val south = latitude(tile.y + 1)
        val bbox = "${south},${west},${north},${east}"
        return "[out:json][timeout:12];way[highway~\"^(motorway|trunk|primary|secondary|tertiary|residential|unclassified|service|motorway_link|trunk_link|primary_link|secondary_link)$\"]($bbox);out geom($bbox);"
    }

    // Public instances can be temporarily unavailable or rate limited.
    // Only one small tile is attempted per GPS position; never fan out requests.
    private val ENDPOINTS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter"
    )

    fun writeTile(tile: Tile, writer: BufferedWriter): Int {
        val payload = "data=" + java.net.URLEncoder.encode(query(tile), "UTF-8")
        var lastError: IOException? = null
        for (endpoint in ENDPOINTS) {
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 9_000
                readTimeout = 23_000
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "MotoGPS-Android/0.3 (OSM road context)")
            }
            try {
                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code !in 200..299) {
                    val body = conn.errorStream?.bufferedReader()?.use { it.readText().take(300) }
                        ?.replace('\n', ' ')?.replace('\r', ' ')?.trim().orEmpty()
                    throw IOException("Overpass HTTP $code at $endpoint: $body")
                }
                val response = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                // A successful response with zero streets is valid; do not retry
                // against another public server if the query actually succeeded.
                return writeOsmWays(response, writer)
            } catch (e: IOException) {
                lastError = e
                android.util.Log.w("GatewayRoadTiles", "OSM ${tile.key}: ${e.message}")
            } catch (e: org.json.JSONException) {
                lastError = IOException("Risposta Overpass non valida da $endpoint", e)
                android.util.Log.w("GatewayRoadTiles", lastError?.message ?: "Risposta Overpass non valida")
            } finally {
                conn.disconnect()
            }
        }
        throw lastError ?: IOException("Impossibile scaricare tile OSM ${tile.key}")
    }

    /** Converts an actual Overpass 'out geom' response to the existing BLE map cache. */
    fun writeOsmWays(response: JSONObject, writer: BufferedWriter): Int {
        if (response.optJSONArray("elements") == null)
            throw IOException("Risposta Overpass priva di geometrie OSM")
        val ways = response.getJSONArray("elements")
        var written = 0
        for (i in 0 until ways.length()) {
            val way = ways.optJSONObject(i) ?: continue
            if (way.optString("type") != "way") continue
            val tags = way.optJSONObject("tags") ?: continue
            val roadClass = when (tags.optString("highway")) {
                "motorway", "motorway_link", "trunk", "trunk_link" -> 0
                "primary", "primary_link" -> 1
                "secondary", "secondary_link", "tertiary" -> 2
                "residential", "unclassified", "service" -> 3
                else -> continue
            }
            val geometry = way.optJSONArray("geometry") ?: continue
            if (geometry.length() < 2) continue
            val points = ArrayList<Pair<Int, Int>>(geometry.length())
            for (j in 0 until geometry.length()) {
                val node = geometry.optJSONObject(j) ?: continue
                val lat = node.optDouble("lat", Double.NaN)
                val lon = node.optDouble("lon", Double.NaN)
                if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 ||
                    lon !in -180.0..180.0) continue
                val point = (lat * 1e6).toInt() to (lon * 1e6).toInt()
                if (points.lastOrNull() != point) points.add(point)
            }
            if (points.size < 2) continue
            // Cap output size; roads longer than a tile remain represented by
            // consecutive smaller segments rather than silently being dropped.
            val speed = tags.optString("maxspeed").substringBefore(" ")
                .toIntOrNull()?.takeIf { it in 5..140 } ?: when (roadClass) {
                    0 -> 110; 1 -> 90; 2 -> 70; else -> 50
                }
            for (offset in 0 until points.lastIndex step 2047) {
                val slice = points.subList(offset, minOf(offset + 2048, points.size))
                if (slice.size < 2) continue
                writer.write("$roadClass,$speed|${slice.minOf { it.first }},${slice.minOf { it.second }},${slice.maxOf { it.first }},${slice.maxOf { it.second }}|")
                writer.write(slice.joinToString(";") { "${it.first},${it.second}" })
                writer.newLine()
                written++
            }
        }
        return written
    }
}
