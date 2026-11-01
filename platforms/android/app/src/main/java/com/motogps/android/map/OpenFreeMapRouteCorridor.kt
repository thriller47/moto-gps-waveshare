package com.motogps.android.map

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sinh
import kotlin.math.tan

/** Explicit offline download along a Valhalla route, never invoked from GPS updates. */
internal object OpenFreeMapRouteCorridor {
    const val ZOOM = 14
    const val MAX_TILES = 3000
    const val MAX_BYTES = 50L * 1024 * 1024
    data class Tile(val x: Int, val y: Int) {
        val filename get() = "ofm_trial_${ZOOM}_${x}_${y}.motomap"
    }

    fun plan(route: JSONObject): List<Tile> {
        val geometry = route.optJSONArray("geometry_gcj02")
            ?: route.optJSONArray("polyline") ?: route.optJSONArray("coordinates")
            ?: return emptyList()
        val locations = ArrayList<Pair<Double, Double>>()
        for (i in 0 until geometry.length()) {
            val obj = geometry.optJSONObject(i)
            val array = geometry.optJSONArray(i)
            val lat = obj?.optDouble("latitude_deg",
                obj.optDouble("latitude", Double.NaN))
                ?: array?.optDouble(1, Double.NaN) ?: Double.NaN
            val lon = obj?.optDouble("longitude_deg",
                obj.optDouble("longitude", Double.NaN))
                ?: array?.optDouble(0, Double.NaN) ?: Double.NaN
            if (lat.isFinite() && lon.isFinite() && lat in -85.0..85.0 &&
                lon in -180.0..180.0) locations.add(lat to lon)
        }
        if (locations.size < 2) return emptyList()
        val tiles = LinkedHashSet<Tile>()
        fun tilePoint(lat: Double, lon: Double): Pair<Double, Double> {
            val scale = (1 shl ZOOM).toDouble()
            val latRad = Math.toRadians(lat)
            return ((lon + 180.0) / 360.0 * scale) to
                ((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * scale)
        }
        var previous = tilePoint(locations.first().first, locations.first().second)
        for ((lat, lon) in locations) {
            val next = tilePoint(lat, lon)
            // Sample in tile-coordinate space: covers long route segments without
            // downloading an entire bounding rectangle (which could be enormous).
            val steps = maxOf(1, (maxOf(
                kotlin.math.abs(next.first - previous.first),
                kotlin.math.abs(next.second - previous.second)) * 8).toInt() + 1)
            if (steps > 100000) throw IOException("Geometria percorso non valida")
            for (step in 0..steps) {
                val f = step.toDouble() / steps
                val x = floor(previous.first + (next.first - previous.first) * f).toInt()
                val y = floor(previous.second + (next.second - previous.second) * f).toInt()
                for (dx in -1..1) for (dy in -1..1) {
                    val tx = x + dx
                    val ty = y + dy
                    if (tx in 0 until (1 shl ZOOM) && ty in 0 until (1 shl ZOOM)) {
                        tiles.add(Tile(tx, ty))
                        if (tiles.size > MAX_TILES)
                            throw IOException("Corridoio oltre 3000 tile: usa MotoMap ZIP")
                    }
                }
            }
            previous = next
        }
        return tiles.toList()
    }

    fun missing(context: Context, tiles: List<Tile>): List<Tile> {
        val dir = HybridMapRepository.getSharedMapsDir(context)
        return tiles.filter { !File(dir, it.filename).isFile }
    }

    /** Single-thread download; prevents exceeding the public offline limits. */
    fun download(context: Context, tiles: List<Tile>, progress: (Int, Int, Long) -> Unit) {
        var bytes = 0L
        for ((index, tile) in tiles.withIndex()) {
            if (Thread.currentThread().isInterrupted) throw IOException("Interrotto")
            val item = OpenFreeMapTileProbe.downloadTile(
                context, ZOOM, tile.x, tile.y,
                remainingBytes = MAX_BYTES - bytes
            )
            bytes += item.downloadedBytes
            progress(index + 1, tiles.size, bytes)
        }
    }
}
