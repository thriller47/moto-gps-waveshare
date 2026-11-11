package com.motogps.android.map

import android.content.Context
import com.motogps.android.diagnostics.DiagnosticLog
import org.json.JSONObject
import java.io.File
import java.io.IOException
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
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

    /**
     * Imported MotoMap archives contain z15 road tiles whereas this corridor uses z14.
     * A z14 tile is considered covered only if all four z15 children are present
     * (possibly in different archives). A partial or unreadable archive must NOT
     * suppress an OpenFreeMap download.
     */
    internal fun coveredByMotoMap(tile: Tile, available: Set<String>): Boolean {
        val x = tile.x * 2
        val y = tile.y * 2
        return (0..1).all { dx ->
            (0..1).all { dy -> "tile_15_${x + dx}_${y + dy}.motomap" in available }
        }
    }

    fun missing(context: Context, tiles: List<Tile>): List<Tile> {
        val dir = HybridMapRepository.getSharedMapsDir(context)
        val candidates = tiles.filter { !File(dir, it.filename).isFile }
        val cached = tiles.size - candidates.size
        if (candidates.isEmpty()) {
            DiagnosticLog.event("OFM_COVERAGE", "planned=${tiles.size} cached=$cached motoMap=0 missing=0")
            return emptyList()
        }

        // Query only the entries actually needed for the planned route; do not
        // enumerate/extract hundreds of thousands of Italian MotoMap tiles.
        val wanted = HashSet<String>()
        for (tile in candidates) {
            val x = tile.x * 2
            val y = tile.y * 2
            for (dx in 0..1) for (dy in 0..1) {
                wanted.add("tile_15_${x + dx}_${y + dy}.motomap")
            }
        }
        val found = HashSet<String>()
        val certified = HashSet<Tile>()
        val packs = MotomapPackImporter.packagesDir(context).listFiles { file ->
            file.isFile && file.name.endsWith(".motomap.zip", ignoreCase = true)
        }.orEmpty()
        for (pack in packs) {
            try {
                java.util.zip.ZipFile(pack).use { zip ->
                    // Strict MotoMap v4 only; old ZIPs may still exist on disk
                    // but must not certify coverage or claim valid road tiles.
                    if (MotomapPackImporter.isV4Pack(zip)) {
                        val metadata = zip.getEntry(MotomapPackImporter.METADATA)
                        val coverage = zip.getEntry(MotomapPackImporter.COVERAGE)
                        if (metadata != null && coverage != null &&
                            coverage.size in 1L..MotomapPackImporter.MAX_COVERAGE_BYTES) {
                            val meta = JSONObject(zip.getInputStream(metadata)
                                .bufferedReader().use { it.readText() })
                            if (meta.optString("coverage") == "poly-full-z14") {
                                val relevant = candidates.toHashSet()
                                zip.getInputStream(coverage).bufferedReader().useLines { lines ->
                                    lines.forEach { line ->
                                        val parts = line.split(',')
                                        if (parts.size == 2) {
                                            val x = parts[0].toIntOrNull()
                                            val y = parts[1].toIntOrNull()
                                            if (x != null && y != null &&
                                                x in 0 until (1 shl ZOOM) &&
                                                y in 0 until (1 shl ZOOM)) {
                                                val candidate = Tile(x, y)
                                                if (candidate in relevant) certified.add(candidate)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        for (name in wanted) {
                            if (name in found) continue
                            val entry = zip.getEntry(name) ?: continue
                            if (entry.size in 1L..(5L * 1024 * 1024)) found.add(name)
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("OpenFreeMapCorridor",
                    "Cannot inspect MotoMap pack ${pack.name}; retain download fallback", e)
            }
        }
        val missing = candidates.filterNot { it in certified || coveredByMotoMap(it, found) }
        DiagnosticLog.event("OFM_COVERAGE",
            "planned=${tiles.size} cached=$cached motoMap=${candidates.size - missing.size} certified=${candidates.count { it in certified }} missing=${missing.size} packs=${packs.size}")
        for (tile in missing) {
            val x = tile.x * 2
            val y = tile.y * 2
            val expected = (0..1).flatMap { dx ->
                (0..1).map { dy -> "tile_15_${x + dx}_${y + dy}.motomap" }
            }
            DiagnosticLog.event("OFM_COVERAGE_MISSING",
                "z14=${ZOOM}/${tile.x}/${tile.y} file=${tile.filename} " +
                    "missingMotoMap=${expected.filterNot { it in found }.joinToString(",")} " +
                    "presentMotoMap=${expected.count { it in found }}/4")
        }
        return missing
    }

    /** Single-thread download; prevents exceeding the public offline limits. */
    fun download(context: Context, tiles: List<Tile>, progress: (Int, Int, Long) -> Unit) {
        require(tiles.size <= MAX_TILES) { "Corridoio oltre 3000 tile" }
        var bytes = 0L
        for ((index, tile) in tiles.withIndex()) {
            if (bytes >= MAX_BYTES) throw IOException("Limite download 50 MB raggiunto")
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
