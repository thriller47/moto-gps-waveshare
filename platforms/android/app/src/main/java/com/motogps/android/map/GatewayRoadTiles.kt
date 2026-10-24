package com.motogps.android.map

import android.util.Log
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

    fun corridor(points: List<Pair<Double, Double>>): List<Tile> {
        if (points.isEmpty()) return emptyList()
        val visited = linkedSetOf<Tile>()
        for (i in points.indices) {
            val current = points[i]
            val previous = if (i == 0) current else points[i - 1]
            val northMeters = (current.first - previous.first) * 111_320.0
            val eastMeters = (current.second - previous.second) * 111_320.0 *
                cos(Math.toRadians(current.first))
            val distance = kotlin.math.hypot(northMeters, eastMeters)
            val steps = kotlin.math.ceil(distance / 250.0).toInt().coerceIn(1, 500)
            for (step in 0..steps) {
                val t = step.toDouble() / steps
                val lat = previous.first + (current.first - previous.first) * t
                val lon = previous.second + (current.second - previous.second) * t
                visited.addAll(around(lat, lon, 1))
            }
        }
        return visited.toList()
    }

    /** Writes only verified, provider-supplied coordinates; 0 is valid for an empty tile. */
    fun writeTile(gatewayBaseUrl: String, tile: Tile, writer: BufferedWriter): Int {
        val base = gatewayBaseUrl.trimEnd('/')
        if (!base.startsWith("https://")) throw IOException("Gateway mappe non configurato (HTTPS richiesto)")
        var conn: HttpURLConnection? = null
        try {
            conn = URL("$base/v1/map/tiles/$ZOOM/${tile.x}/${tile.y}")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 4000
            conn.readTimeout = 8500
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "MotoGPS/1.0 (Android vector map)")
            val status = conn.responseCode
            if (status !in 200..299) {
                val error = try {
                    JSONObject(conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "{}")
                        .optJSONObject("error")?.optString("code").orEmpty()
                } catch (_: Exception) { "" }
                throw IOException("Gateway tile HTTP $status ${error}".trim())
            }
            val data = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            if (data.optInt("schema_version") != 1 || !data.has("roads")) {
                throw IOException("Risposta gateway mappe non valida")
            }
            val roads = data.optJSONArray("roads") ?: throw IOException("Elenco strade mancante")
            var written = 0
            for (i in 0 until roads.length()) {
                val road = roads.optJSONObject(i) ?: continue
                val roadClass = when (road.optString("class")) {
                    "motorway" -> 0
                    "primary" -> 1
                    "secondary" -> 2
                    "residential" -> 3
                    // Access streets are legitimate context around a rider's home.
                    "service" -> 3
                    else -> 5
                }
                if (roadClass !in 0..3) continue
                val vertices = road.optJSONArray("points_e6") ?: continue
                if (vertices.length() !in 2..2048) continue
                val points = ArrayList<Pair<Int, Int>>(vertices.length())
                for (j in 0 until vertices.length()) {
                    val pair = vertices.optJSONArray(j) ?: continue
                    if (pair.length() != 2) continue
                    val latE6 = pair.optInt(0, Int.MAX_VALUE)
                    val lonE6 = pair.optInt(1, Int.MAX_VALUE)
                    if (latE6 in -90_000_000..90_000_000 && lonE6 in -180_000_000..180_000_000) {
                        points.add(latE6 to lonE6)
                    }
                }
                if (points.size < 2) continue
                val speed = when (roadClass) { 0 -> 130; 1 -> 90; 2 -> 70; else -> 50 }
                writer.write("$roadClass,$speed|${points.minOf { it.first }},${points.minOf { it.second }},${points.maxOf { it.first }},${points.maxOf { it.second }}|")
                writer.write(points.joinToString(";") { "${it.first},${it.second}" })
                writer.newLine()
                written++
            }
            return written
        } catch (e: Exception) {
            Log.w("GatewayRoadTiles", "Tile ${tile.key}: ${e.message}")
            throw e
        } finally {
            conn?.disconnect()
        }
    }
}
