package com.motogps.android.map

import android.content.Context
import android.util.Log
import com.motogps.android.jni.GeoPointE6
import java.io.File
import java.util.zip.ZipFile
import java.util.Locale

data class SavedMapInfo(
    val fileName: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    /** true for a compressed, imported .motomap.zip (not an individual tile). */
    val isPackage: Boolean = false
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

/** Offline road cache backed by real OSM ways fetched from Overpass, never Render. */
class HybridMapRepository(
    private val context: Context
) {
    companion object {

        fun getSharedMapsDir(context: Context): File {
            val dir = File(context.filesDir, "maps")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }
    }

    private val mapsDir: File by lazy { getSharedMapsDir(context) }

    fun listSavedMaps(): List<SavedMapInfo> {
        // Legacy individual tiles/cache are stored directly under maps/.
        val individual = mapsDir.listFiles { file ->
            file.isFile && (file.name.endsWith(".motomap", ignoreCase = true) ||
                file.name.endsWith(".json", ignoreCase = true))
        }?.map { file ->
            SavedMapInfo(file.name, file.length(), file.lastModified())
        }.orEmpty()

        // Imported regional/national ZIPs are *not* extracted: see the
        // compressed tile reader in loadFromRegionalFiles(). They live in a
        // hidden subfolder and were previously omitted from this list.
        val packages = MotomapPackImporter.packagesDir(context)
            .listFiles { file ->
                file.isFile && file.name.endsWith(".motomap.zip", ignoreCase = true)
            }?.map { file ->
                SavedMapInfo(file.name, file.length(), file.lastModified(),
                    isPackage = true)
            }.orEmpty()
        return (packages + individual).sortedByDescending { it.lastModifiedMs }
    }

    fun deleteSavedMap(fileName: String): Boolean {
        // Only accept a displayed basename; never allow a path supplied by a
        // caller to traverse maps/ or .osm_packages/.
        if (fileName.isBlank() || fileName != File(fileName).name ||
            fileName.contains('\\') || fileName == "." || fileName == "..") return false

        val isPackage = fileName.endsWith(".motomap.zip", ignoreCase = true)
        val dir = if (isPackage) MotomapPackImporter.packagesDir(context) else mapsDir
        val file = File(dir, fileName)
        if (!file.isFile || file.canonicalFile.parentFile != dir.canonicalFile) return false
        if (!isPackage && !(fileName.endsWith(".motomap", ignoreCase = true) ||
                fileName.endsWith(".json", ignoreCase = true))) return false

        val deleted = file.delete()
        if (deleted && fileName.endsWith(".motomap", ignoreCase = true)) {
            val base = fileName.removeSuffix("_parziale.motomap").removeSuffix(".motomap")
            File(mapsDir, ".${base}_parts").deleteRecursively()
        }
        return deleted
    }

    /** Fetch a small nearby road window without blocking the navigation loop. */
    fun ensureOnlineTile(lat: Double, lon: Double): Boolean {
        val center = GatewayRoadTiles.tileFor(lat, lon)
        val file = File(mapsDir, "osm_near_${center.key}.motomap")
        if (file.exists() && file.length() > 0L &&
            System.currentTimeMillis() - file.lastModified() < 7L * 24L * 60L * 60L * 1000L) {
            return true
        }
        val temp = File(mapsDir, file.name + ".tmp")
        var count = 0
        var failures = 0
        try {
            temp.bufferedWriter().use { writer ->
                for (tile in listOf(center)) {
                    try {
                        count += GatewayRoadTiles.writeTile(tile, writer)
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
            Log.i("HybridMapRepository", "OSM nearby cache: $count roads")
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
        // ZipFile resolves individual tile names using the ZIP central directory:
        // no extraction and no scan of every Italian road on each GPS update.
        val packageDir = MotomapPackImporter.packagesDir(context)
        val packFiles = packageDir.listFiles { file ->
            file.isFile && file.name.endsWith(".motomap.zip", ignoreCase = true)
        }?.toList().orEmpty()
        val wantedNames = nearTiles.map { "tile_${it.key}.motomap" }
        for (archive in packFiles) {
            try {
                ZipFile(archive).use { zip ->
                    for (name in wantedNames) {
                        val entry = zip.getEntry(name) ?: continue
                        if (entry.size <= 0L || entry.size > 5L * 1024 * 1024) continue
                        zip.getInputStream(entry).bufferedReader().useLines { lines ->
                            for (line in lines) {
                                parseRoadLine(line, queryMinLatE6, queryMinLonE6,
                                    queryMaxLatE6, queryMaxLonE6, seenRoads, results)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("HybridMapRepository", "Error reading pack ${archive.name}: ${e.message}")
            }
        }
        for (file in mapFiles + checkpointFiles) {
            try {
                file.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        parseRoadLine(line, queryMinLatE6, queryMinLonE6,
                            queryMaxLatE6, queryMaxLonE6, seenRoads, results)
                    }
                }
            } catch (e: Exception) {
                Log.e("HybridMapRepository", "Error reading file ${file.name}: ${e.message}")
            }
        }
        return results
    }

    private fun parseRoadLine(
        line: String, queryMinLatE6: Int, queryMinLonE6: Int,
        queryMaxLatE6: Int, queryMaxLonE6: Int,
        seenRoads: MutableSet<String>, results: MutableList<MapRoadSegment>
    ) {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return
        if (!seenRoads.add(trimmed)) return

        val parts = trimmed.split("|")
        if (parts.size < 3) return

        val headerParts = parts[0].split(",")
        if (headerParts.size < 2) return
        val roadClass = headerParts[0].toIntOrNull() ?: 5
        val maxSpeedKph = headerParts[1].toIntOrNull() ?: getDefaultSpeedLimitForClass(roadClass)

        val bboxParts = parts[1].split(",")
        if (bboxParts.size < 4) return
        val minLatE6 = bboxParts[0].toIntOrNull() ?: return
        val minLonE6 = bboxParts[1].toIntOrNull() ?: return
        val maxLatE6 = bboxParts[2].toIntOrNull() ?: return
        val maxLonE6 = bboxParts[3].toIntOrNull() ?: return

        // Spatial BBox filter check
        if (maxLatE6 < queryMinLatE6 || minLatE6 > queryMaxLatE6 ||
            maxLonE6 < queryMinLonE6 || minLonE6 > queryMaxLonE6
        ) {
            return
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

    private fun String?.isNull_or_blank(): Boolean = this == null || this.trim().isEmpty()
}
