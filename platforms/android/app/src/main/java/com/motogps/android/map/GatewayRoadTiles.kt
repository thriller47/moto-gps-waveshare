package com.motogps.android.map

import android.util.Log
import org.json.JSONObject
import java.io.BufferedWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Obtain real OSM street geometry from our existing gateway z15 vector tiles.
 * It is a bounded nearby fallback, not a substitute for a full regional extract.
 */
internal object GatewayRoadTiles {
    private const val ZOOM = 15
    private const val GRID_SIZE = 1 shl ZOOM
    private const val MAX_FAILURES = 2

    fun writeNearby(gatewayBaseUrl: String, latitude: Double, longitude: Double, output: BufferedWriter): Int {
        if (!gatewayBaseUrl.startsWith("https://") || !latitude.isFinite() || !longitude.isFinite()) {
            return 0
        }
        val centerX = floor((longitude + 180.0) / 360.0 * GRID_SIZE).toInt()
        val latRad = Math.toRadians(latitude.coerceIn(-85.0, 85.0))
        val centerY = floor((1.0 - ln(tan(latRad) + 1.0 / kotlin.math.cos(latRad)) / PI) / 2.0 * GRID_SIZE).toInt()
        val base = gatewayBaseUrl.trimEnd('/')
        var written = 0
        var consecutiveErrors = 0
        for (dy in -1..1) {
            for (dx in -1..1) {
                val x = centerX + dx
                val y = centerY + dy
                if (x !in 0 until GRID_SIZE || y !in 0 until GRID_SIZE) continue
                var connection: HttpURLConnection? = null
                try {
                    connection = URL("$base/v1/map/tiles/$ZOOM/$x/$y")
                        .openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 3500
                    connection.readTimeout = 6500
                    connection.setRequestProperty("Accept", "application/json")
                    connection.setRequestProperty("User-Agent", "MotoGPS/1.0 (Android offline vector map cache)")
                    if (connection.responseCode !in 200..299) {
                        throw java.io.IOException("Gateway tile HTTP ${connection.responseCode}")
                    }
                    val payload = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    val roads = payload.optJSONArray("roads")
                    if (roads != null) {
                        for (i in 0 until roads.length()) {
                            val road = roads.optJSONObject(i) ?: continue
                            val roadClass = when (road.optString("class")) {
                                "motorway" -> 0
                                "primary" -> 1
                                "secondary" -> 2
                                "residential" -> 3
                                "service" -> 4
                                else -> 5
                            }
                            if (roadClass !in 0..3) continue
                            val inputPoints = road.optJSONArray("points_e6") ?: continue
                            val points = ArrayList<Pair<Int, Int>>(inputPoints.length())
                            for (j in 0 until inputPoints.length()) {
                                val pair = inputPoints.optJSONArray(j) ?: continue
                                if (pair.length() != 2) continue
                                val latE6 = pair.optInt(0)
                                val lonE6 = pair.optInt(1)
                                if (latE6 in -90_000_000..90_000_000 &&
                                    lonE6 in -180_000_000..180_000_000) {
                                    points.add(Pair(latE6, lonE6))
                                }
                            }
                            if (points.size < 2) continue
                            val minLat = points.minOf { it.first }
                            val minLon = points.minOf { it.second }
                            val maxLat = points.maxOf { it.first }
                            val maxLon = points.maxOf { it.second }
                            val speed = when (roadClass) {
                                0 -> 130
                                1 -> 90
                                2 -> 70
                                else -> 50
                            }
                            output.write("$roadClass,$speed|$minLat,$minLon,$maxLat,$maxLon|")
                            output.write(points.joinToString(";") { "${it.first},${it.second}" })
                            output.newLine()
                            written++
                        }
                    }
                    consecutiveErrors = 0
                } catch (e: Exception) {
                    consecutiveErrors++
                    Log.w("GatewayRoadTiles", "Gateway OSM tile $ZOOM/$x/$y failed: ${e.message}")
                    if (consecutiveErrors >= MAX_FAILURES) {
                        return written
                    }
                } finally {
                    connection?.disconnect()
                }
            }
        }
        return written
    }
}
