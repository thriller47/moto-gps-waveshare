package com.motogps.android.map

import android.content.Context
import android.util.Log
import com.motogps.android.jni.GeoPointE6
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex

data class SavedMapInfo(
    val fileName: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long
)

data class DownloadResult(
    val file: File,
    val totalSegments: Int,
    val completedSectors: Int = 0,
    val totalSectors: Int = 0,
    val isComplete: Boolean = true
)

data class MapRoadSegment(
    val roadClass: Int,
    val maxSpeedKph: Int,
    val minLatE6: Int,
    val minLonE6: Int,
    val maxLatE6: Int,
    val maxLonE6: Int,
    val points: List<GeoPointE6>
)

data class BoundingBox(
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double
)

/** Offline road cache backed exclusively by real vector tiles from our gateway. */
class HybridMapRepository(
    private val context: Context,
    private val gatewayBaseUrl: String? = null
) {
    companion object {
        private val offlineDownloadMutex = Mutex()
        // Cap each manual download batch, then resume remaining tiles next time.
        private const val MAX_TILES_PER_BATCH = 24

        fun getSharedMapsDir(context: Context): File {
            val dir = File(context.filesDir, "maps")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }
    }

    private val mapsDir: File by lazy { getSharedMapsDir(context) }

    fun listSavedMaps(): List<SavedMapInfo> {
        val files = mapsDir.listFiles { _, name ->
            name.endsWith(".motomap") || name.endsWith(".json")
        } ?: return emptyList()
        return files.map { file ->
            SavedMapInfo(file.name, file.length(), file.lastModified())
        }.sortedByDescending { it.lastModifiedMs }
    }

    fun deleteSavedMap(fileName: String): Boolean {
        val file = File(mapsDir, fileName)
        if (!file.exists() || file.parentFile?.canonicalFile != mapsDir.canonicalFile) return false
        val deleted = file.delete()
        if (deleted && fileName.endsWith(".motomap")) {
            val base = fileName.removeSuffix("_parziale.motomap").removeSuffix(".motomap")
            File(mapsDir, ".${base}_parts").deleteRecursively()
        }
        return deleted
    }

    /** Fetch a small nearby road window without blocking the navigation loop. */
    fun ensureOnlineTile(lat: Double, lon: Double): Boolean {
        val gateway = gatewayBaseUrl
        if (gateway.isNullOrBlank()) {
            Log.w("HybridMapRepository", "Gateway mappe non configurato")
            return false
        }
        val center = GatewayRoadTiles.tileFor(lat, lon)
        val file = File(mapsDir, "gateway_near_${center.key}.motomap")
        if (file.exists() && file.length() > 0L &&
            System.currentTimeMillis() - file.lastModified() < 7L * 24L * 60L * 60L * 1000L) {
            return true
        }
        val temp = File(mapsDir, file.name + ".tmp")
        var count = 0
        var failures = 0
        try {
            temp.bufferedWriter().use { writer ->
                for (tile in GatewayRoadTiles.around(lat, lon, 1)) {
                    try {
                        count += GatewayRoadTiles.writeTile(gateway, tile, writer)
                        failures = 0
                    } catch (_: Exception) {
                        failures++
                        if (failures >= 2) break
                    }
                }
            }
            if (count < 1) {
                Log.w("HybridMapRepository", "Gateway mappe: nessuna strada disponibile in zona")
                return false
            }
            if (file.exists() && !file.delete()) return false
            if (!temp.renameTo(file)) return false
            Log.i("HybridMapRepository", "Gateway OSM nearby cache: $count roads")
            return true
        } finally {
            temp.delete()
        }
    }

    fun loadFromRegionalFiles(
        queryMinLatE6: Int,
        queryMinLonE6: Int,
        queryMaxLatE6: Int,
        queryMaxLonE6: Int
    ): List<MapRoadSegment> {
        val results = mutableListOf<MapRoadSegment>()
        val mapFiles = mapsDir.listFiles { _, name -> name.endsWith(".motomap") }
            ?.toList().orEmpty()
        // Route-corridor downloads checkpoint each tile immediately but only
        // assemble the final .motomap when the entire download finishes.
        // Read already-completed nearby tiles as well: otherwise the rider can
        // leave the 8 km offline area while the next corridor is still downloading.
        val midLat = (queryMinLatE6.toLong() + queryMaxLatE6.toLong()) / 2_000_000.0
        val midLon = (queryMinLonE6.toLong() + queryMaxLonE6.toLong()) / 2_000_000.0
        val nearTiles = GatewayRoadTiles.around(midLat, midLon, 2)
        val checkpointFiles = mapsDir.listFiles { file ->
            file.isDirectory && file.name.endsWith("_parts")
        }?.flatMap { folder ->
            nearTiles.map { tile -> File(folder, "tile_${tile.key}.motomap") }
                .filter { it.isFile && it.length() > 0L }
        }.orEmpty()
        val seenRoads = HashSet<String>()
        for (file in mapFiles + checkpointFiles) {
            try {
                file.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                        if (!seenRoads.add(trimmed)) continue

                        val parts = trimmed.split("|")
                        if (parts.size < 3) continue

                        val headerParts = parts[0].split(",")
                        if (headerParts.size < 2) continue
                        val roadClass = headerParts[0].toIntOrNull() ?: 5
                        val maxSpeedKph = headerParts[1].toIntOrNull() ?: getDefaultSpeedLimitForClass(roadClass)

                        val bboxParts = parts[1].split(",")
                        if (bboxParts.size < 4) continue
                        val minLatE6 = bboxParts[0].toIntOrNull() ?: continue
                        val minLonE6 = bboxParts[1].toIntOrNull() ?: continue
                        val maxLatE6 = bboxParts[2].toIntOrNull() ?: continue
                        val maxLonE6 = bboxParts[3].toIntOrNull() ?: continue

                        // Spatial BBox filter check
                        if (maxLatE6 < queryMinLatE6 || minLatE6 > queryMaxLatE6 ||
                            maxLonE6 < queryMinLonE6 || minLonE6 > queryMaxLonE6
                        ) {
                            continue
                        }

                        val coordStr = parts[2]
                        val points = parseCoordinates(coordStr)
                        if (points.isNotEmpty()) {
                            results.add(
                                MapRoadSegment(
                                    roadClass = roadClass,
                                    maxSpeedKph = maxSpeedKph,
                                    minLatE6 = minLatE6,
                                    minLonE6 = minLonE6,
                                    maxLatE6 = maxLatE6,
                                    maxLonE6 = maxLonE6,
                                    points = points
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("HybridMapRepository", "Error reading file ${file.name}: ${e.message}")
            }
        }
        return results
    }

    private fun parseCoordinates(coordStr: String): List<GeoPointE6> {
        val points = mutableListOf<GeoPointE6>()
        val pairs = coordStr.split(";")
        for (pair in pairs) {
            val coords = pair.split(",")
            if (coords.size < 2) continue
            val lat = parseCoordComponent(coords[0])
            val lon = parseCoordComponent(coords[1])
            if (lat != null && lon != null) {
                points.add(GeoPointE6(lat, lon))
            }
        }
        return points
    }

    private fun parseCoordComponent(str: String): Int? {
        val trimmed = str.trim()
        if (trimmed.contains(".")) {
            val d = trimmed.toDoubleOrNull() ?: return null
            return (d * 1e6).toInt()
        }
        return trimmed.toIntOrNull()
    }

    fun parseHighwayClass(highway: String?): Int {
        return when (highway?.lowercase(Locale.ROOT)) {
            "motorway", "motorway_link", "trunk", "trunk_link" -> 0
            "primary", "primary_link" -> 1
            "secondary", "secondary_link", "tertiary", "tertiary_link" -> 2
            "residential", "unclassified" -> 3
            "service", "living_street", "pedestrian", "track", "path", "footway", "cycleway" -> 4
            else -> 5
        }
    }

    fun parseMaxSpeedTag(maxspeed: String?, roadClass: Int): Int {
        if (maxspeed.isNull_or_blank()) return getDefaultSpeedLimitForClass(roadClass)
        val clean = maxspeed?.trim()?.lowercase(Locale.ROOT) ?: ""
        if (clean == "none" || clean == "signals") return 130
        if (clean == "walk" || clean == "living_street") return 20
        if (clean.endsWith("mph")) {
            val valStr = clean.replace("mph", "").trim()
            val mph = valStr.toIntOrNull()
            if (mph != null) return (mph * 1.60934).toInt()
        }
        val numberMatch = Regex("(\\d+)").find(clean)
        if (numberMatch != null) {
            val num = numberMatch.groupValues[1].toIntOrNull()
            if (num != null && num > 0) return num
        }
        if (clean.contains("urban")) return 50
        if (clean.contains("rural")) return 90
        if (clean.contains("motorway")) return 130
        return getDefaultSpeedLimitForClass(roadClass)
    }

    fun getDefaultSpeedLimitForClass(roadClass: Int): Int {
        return when (roadClass) {
            0 -> 130
            1 -> 90
            2 -> 70
            3 -> 50
            4 -> 30
            else -> 50
        }
    }

    private suspend fun downloadGatewayTiles(
        tiles: List<GatewayRoadTiles.Tile>,
        target: File,
        label: String,
        onProgress: ((Int, Int, String) -> Unit)?
    ): DownloadResult {
        val gateway = gatewayBaseUrl
        if (gateway.isNullOrBlank()) {
            throw IOException("Gateway mappe non configurato. Configura l'indirizzo del server nell'app.")
        }
        if (tiles.isEmpty()) throw IOException("Nessuna tile richiesta")
        offlineDownloadMutex.lock()
        try {
            val partsDir = File(mapsDir, ".${target.nameWithoutExtension}_parts")
            if (!partsDir.exists() && !partsDir.mkdirs()) {
                throw IOException("Impossibile creare la cache mappe")
            }
            var completed = 0
            var totalSegments = 0
            var attempts = 0
            var consecutiveFailures = 0
            var lastGatewayError: String? = null
            for ((index, tile) in tiles.withIndex()) {
                currentCoroutineContext().ensureActive()
                val part = File(partsDir, "tile_${tile.key}.motomap")
                if (part.exists() && part.length() > 0L) {
                    completed++
                    totalSegments += part.useLines { lines ->
                        lines.count { it.isNotBlank() && !it.startsWith("#") }
                    }
                    onProgress?.invoke(index + 1, tiles.size, "$label: $completed/${tiles.size} tile")
                    continue
                }
                // A batch is a pacing boundary, not the end of a download.
                // Preserve checkpoints and continue automatically.
                if (consecutiveFailures >= 3) break
                if (attempts > 0 && attempts % MAX_TILES_PER_BATCH == 0) {
                    onProgress?.invoke(index, tiles.size, "$label: pausa tra blocchi ($completed/${tiles.size} tile)")
                    delay(1_500)
                }
                attempts++
                val temp = File(partsDir, part.name + ".tmp")
                try {
                    val count = temp.bufferedWriter().use { writer ->
                        writer.write("# Gateway vector tile ${tile.key}\n")
                        GatewayRoadTiles.writeTile(gateway, tile, writer)
                    }
                    if (!temp.renameTo(part)) throw IOException("Impossibile salvare tile ${tile.key}")
                    completed++
                    totalSegments += count
                    consecutiveFailures = 0
                    Log.i("HybridMapRepository", "$label checkpoint: ${tile.key} strade=$count completate=$completed")
                } catch (e: CancellationException) {
                    temp.delete()
                    throw e
                } catch (e: Exception) {
                    temp.delete()
                    consecutiveFailures++
                    lastGatewayError = e.message
                    Log.w("HybridMapRepository", "$label: tile ${tile.key} non disponibile: ${e.message}")
                }
                onProgress?.invoke(index + 1, tiles.size, "$label: $completed/${tiles.size} tile")
                // Avoid bursts towards Render/PMTiles, including at block edges.
                if (index < tiles.lastIndex) delay(if (consecutiveFailures > 0) 1_000 else 350)
            }

            if (totalSegments == 0) {
                throw IOException(
                    "Nessuna strada scaricata. Gateway vettoriale: ${lastGatewayError ?: "nessuna strada nelle tile disponibili"}.")
            }

            val complete = completed == tiles.size
            val partial = File(mapsDir, "${target.nameWithoutExtension}_parziale.motomap")
            val destination = if (complete) target else partial
            val assembling = File(mapsDir, "${target.name}.assembling")
            try {
                assembling.bufferedWriter().use { out ->
                    for (tile in tiles) {
                        val part = File(partsDir, "tile_${tile.key}.motomap")
                        if (part.exists() && part.length() > 0L) {
                            part.forEachLine { line ->
                                out.write(line)
                                out.newLine()
                            }
                        }
                    }
                }
                val backup = File(mapsDir, destination.name + ".bak")
                backup.delete()
                if (destination.exists() && !destination.renameTo(backup)) {
                    throw IOException("Impossibile proteggere la mappa precedente")
                }
                if (!assembling.renameTo(destination)) {
                    if (backup.exists()) backup.renameTo(destination)
                    throw IOException("Impossibile pubblicare la mappa scaricata")
                }
                backup.delete()
                if (complete) {
                    partial.delete()
                    // Preserve tile checkpoints: the next route can reuse them
                    // without downloading the same streets a second time.
                }
                return DownloadResult(destination, totalSegments, completed, tiles.size, complete)
            } finally {
                assembling.delete()
            }
        } finally {
            offlineDownloadMutex.unlock()
        }
    }

    /**
     * 9x9 z15 tiles: roughly 8 km across at northern Italian latitudes.
     * A bounded batch and geographical checkpoints make retries safe,
     * without attempting an entire region in a single session.
     */
    suspend fun downloadCurrentArea(
        lat: Double,
        lon: Double,
        onProgress: ((Int, Int, String) -> Unit)? = null
    ): DownloadResult = downloadGatewayTiles(
        GatewayRoadTiles.around(lat, lon, 4),
        File(mapsDir, "area_vicina_8km.motomap"),
        "Zona vicina (~8 km)",
        onProgress
    )

    suspend fun downloadRouteCorridor(
        rawRoutePoints: List<Pair<Double, Double>>,
        onProgress: ((Int, Int, String) -> Unit)? = null
    ): DownloadResult {
        if (rawRoutePoints.size < 2) throw IllegalArgumentException("Percorso non disponibile")
        return downloadGatewayTiles(
            GatewayRoadTiles.corridor(rawRoutePoints),
            File(mapsDir, "corridoio_rotta.motomap"),
            "Corridoio percorso",
            onProgress
        )
    }

    suspend fun downloadRegion(
        regionName: String,
        onProgress: ((Int, Int, String) -> Unit)? = null
    ): DownloadResult {
        throw UnsupportedOperationException(
            "Download regionale temporaneamente non disponibile: serve un archivio offline regionale dedicato, non migliaia di tile dal gateway.")
    }

    private fun String?.isNull_or_blank(): Boolean = this == null || this.trim().isEmpty()
}
