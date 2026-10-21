package com.motogps.android.map

import android.content.Context
import android.util.Log
import com.motogps.android.jni.GeoPointE6
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.zip.GZIPInputStream
import org.json.JSONObject

data class SavedMapInfo(
    val fileName: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long
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

class HybridMapRepository(private val context: Context) {

    private val mirrors = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter"
    )

    private val mapsDir: File by lazy {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "maps")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        dir
    }

    fun listSavedMaps(): List<SavedMapInfo> {
        val files = mapsDir.listFiles { _, name -> name.endsWith(".motomap") } ?: return emptyList()
        return files.map { file ->
            SavedMapInfo(
                fileName = file.name,
                sizeBytes = file.length(),
                lastModifiedMs = file.lastModified()
            )
        }.sortedByDescending { it.lastModifiedMs }
    }

    fun deleteSavedMap(fileName: String): Boolean {
        val file = File(mapsDir, fileName)
        return if (file.exists() && file.canonicalPath.startsWith(mapsDir.canonicalPath)) {
            file.delete()
        } else {
            false
        }
    }

    fun loadFromRegionalFiles(
        queryMinLatE6: Int,
        queryMinLonE6: Int,
        queryMaxLatE6: Int,
        queryMaxLonE6: Int
    ): List<MapRoadSegment> {
        val results = mutableListOf<MapRoadSegment>()
        val mapFiles = mapsDir.listFiles { _, name -> name.endsWith(".motomap") } ?: return results

        for (file in mapFiles) {
            try {
                file.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

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
            "residential", "living_street", "unclassified" -> 3
            "service", "pedestrian", "track", "path", "footway", "cycleway" -> 4
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

    private fun executeOverpassQuery(query: String): String {
        var lastException: Exception? = null
        val encodedData = "data=" + URLEncoder.encode(query, "UTF-8")

        for (mirror in mirrors) {
            var conn: HttpURLConnection? = null
            try {
                val url = URL(mirror)
                conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 25000
                conn.readTimeout = 25000
                conn.setRequestProperty("Accept-Encoding", "gzip")
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.doOutput = true

                conn.outputStream.use { os ->
                    val bytes = encodedData.toByteArray(Charsets.UTF_8)
                    os.write(bytes, 0, bytes.size)
                }

                val responseCode = conn.responseCode
                if (responseCode in 200..299) {
                    val rawStream: InputStream = conn.inputStream
                    val contentEncoding = conn.getHeaderField("Content-Encoding")
                    val isGzip = contentEncoding?.contains("gzip", ignoreCase = true) == true
                    val finalStream = if (isGzip) GZIPInputStream(rawStream) else rawStream

                    return finalStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                } else {
                    Log.w("HybridMapRepository", "Mirror $mirror returned HTTP $responseCode")
                }
            } catch (e: Exception) {
                Log.w("HybridMapRepository", "Mirror $mirror failed: ${e.message}")
                lastException = e
            } finally {
                conn?.disconnect()
            }
        }
        throw lastException ?: Exception("All Overpass mirrors failed")
    }

    fun downloadCurrentArea(lat: Double, lon: Double, radiusKm: Double = 10.0): File {
        val latDelta = radiusKm / 111.0
        val lonDelta = radiusKm / (111.0 * Math.cos(Math.toRadians(lat)).coerceAtLeast(0.1))

        val minLat = lat - latDelta
        val maxLat = lat + latDelta
        val minLon = lon - lonDelta
        val maxLon = lon + lonDelta

        val fileName = "area_corrente_10km.motomap"
        return downloadBBoxToFile(fileName, minLat, minLon, maxLat, maxLon)
    }

    fun downloadRouteCorridor(rawRoutePoints: List<Pair<Double, Double>>): File {
        if (rawRoutePoints.isEmpty()) {
            throw IllegalArgumentException("La lista punti rotta è vuota")
        }

        val sampledPoints = mutableListOf<Pair<Double, Double>>()
        sampledPoints.add(rawRoutePoints.first())

        var accumulatedDist = 0.0
        for (i in 1 until rawRoutePoints.size) {
            val p1 = rawRoutePoints[i - 1]
            val p2 = rawRoutePoints[i]
            accumulatedDist += calculateDistanceKm(p1.first, p1.second, p2.first, p2.second)
            if (accumulatedDist >= 10.0) {
                sampledPoints.add(p2)
                accumulatedDist = 0.0
            }
        }
        if (sampledPoints.last() != rawRoutePoints.last()) {
            sampledPoints.add(rawRoutePoints.last())
        }

        val lines = mutableSetOf<String>()
        for (pt in sampledPoints) {
            val lat = pt.first
            val lon = pt.second
            val latDelta = 5.0 / 111.0
            val lonDelta = 5.0 / (111.0 * Math.cos(Math.toRadians(lat)).coerceAtLeast(0.1))

            try {
                val subLines = fetchOverpassLinesForBBox(lat - latDelta, lon - lonDelta, lat + latDelta, lon + lonDelta)
                lines.addAll(subLines)
            } catch (e: Exception) {
                Log.w("HybridMapRepository", "Corridor point download failed: ${e.message}")
            }
        }

        val targetFile = File(mapsDir, "corridoio_rotta.motomap")
        targetFile.writeText(lines.joinToString("\n"))
        return targetFile
    }

    private fun calculateDistanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
    }

    fun downloadBBoxToFile(fileName: String, minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): File {
        val lines = fetchOverpassLinesForBBox(minLat, minLon, maxLat, maxLon)
        val targetFile = File(mapsDir, fileName)
        targetFile.writeText(lines.joinToString("\n"))
        return targetFile
    }

    private fun fetchOverpassLinesForBBox(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): List<String> {
        val query = String.format(
            Locale.US,
            "[out:json][timeout:25];(way[\"highway\"](%.6f,%.6f,%.6f,%.6f););out body;>;out skel qt;",
            minLat, minLon, maxLat, maxLon
        )

        val jsonResponse = executeOverpassQuery(query)
        val root = JSONObject(jsonResponse)
        val elements = root.optJSONArray("elements") ?: return emptyList()

        val nodes = mutableMapOf<Long, Pair<Double, Double>>()
        val ways = mutableListOf<JSONObject>()

        for (i in 0 until elements.length()) {
            val elem = elements.getJSONObject(i)
            val type = elem.optString("type")
            if (type == "node") {
                val id = elem.optLong("id")
                val lat = elem.optDouble("lat")
                val lon = elem.optDouble("lon")
                nodes[id] = Pair(lat, lon)
            } else if (type == "way") {
                ways.add(elem)
            }
        }

        val resultLines = mutableListOf<String>()
        for (way in ways) {
            val tags = way.optJSONObject("tags") ?: continue
            val highway = tags.optString("highway", "")
            if (highway.isBlank()) continue

            val roadClass = parseHighwayClass(highway)
            val maxSpeedKph = parseMaxSpeedTag(tags.optString("maxspeed", null), roadClass)

            val nodeIds = way.optJSONArray("nodes") ?: continue
            val wayPoints = mutableListOf<Pair<Double, Double>>()
            var minL = Double.MAX_VALUE
            var maxL = -Double.MAX_VALUE
            var minO = Double.MAX_VALUE
            var maxO = -Double.MAX_VALUE

            for (j in 0 until nodeIds.length()) {
                val nid = nodeIds.optLong(j)
                val nodeCoord = nodes[nid] ?: continue
                wayPoints.add(nodeCoord)
                if (nodeCoord.first < minL) minL = nodeCoord.first
                if (nodeCoord.first > maxL) maxL = nodeCoord.first
                if (nodeCoord.second < minO) minO = nodeCoord.second
                if (nodeCoord.second > maxO) maxO = nodeCoord.second
            }

            if (wayPoints.size >= 2) {
                val minLatE6 = (minL * 1e6).toInt()
                val minLonE6 = (minO * 1e6).toInt()
                val maxLatE6 = (maxL * 1e6).toInt()
                val maxLonE6 = (maxO * 1e6).toInt()

                val ptsStr = wayPoints.joinToString(";") { pt ->
                    String.format(Locale.US, "%.6f,%.6f", pt.first, pt.second)
                }

                resultLines.add("$roadClass,$maxSpeedKph|$minLatE6,$minLonE6,$maxLatE6,$maxLonE6|$ptsStr")
            }
        }

        return resultLines
    }

    val italianRegions = mapOf(
        "Abruzzo" to BoundingBox(41.68, 13.02, 42.89, 14.78),
        "Basilicata" to BoundingBox(39.90, 15.54, 41.14, 16.86),
        "Calabria" to BoundingBox(37.91, 15.62, 40.15, 17.21),
        "Campania" to BoundingBox(39.99, 13.75, 41.51, 15.80),
        "Emilia-Romagna" to BoundingBox(44.02, 9.20, 45.14, 12.76),
        "Friuli-Venezia Giulia" to BoundingBox(45.58, 12.31, 46.65, 13.92),
        "Lazio" to BoundingBox(41.20, 11.45, 42.84, 14.03),
        "Liguria" to BoundingBox(43.79, 7.52, 44.66, 10.05),
        "Lombardia" to BoundingBox(44.68, 8.49, 46.64, 11.42),
        "Marche" to BoundingBox(42.68, 12.15, 43.98, 13.91),
        "Molise" to BoundingBox(41.38, 13.96, 42.06, 15.16),
        "Piemonte" to BoundingBox(44.06, 6.63, 46.46, 9.21),
        "Puglia" to BoundingBox(39.79, 14.90, 41.90, 18.52),
        "Sardegna" to BoundingBox(38.86, 8.13, 41.31, 9.83),
        "Sicilia" to BoundingBox(36.65, 12.43, 38.31, 15.65),
        "Toscana" to BoundingBox(42.24, 9.68, 44.47, 12.37),
        "Trentino-Alto Adige" to BoundingBox(45.67, 10.43, 47.09, 12.48),
        "Umbria" to BoundingBox(42.37, 11.90, 43.62, 13.25),
        "Valle d'Aosta" to BoundingBox(45.45, 6.79, 45.99, 7.94),
        "Veneto" to BoundingBox(44.79, 10.62, 46.67, 13.10)
    )

    fun downloadRegion(regionName: String): File {
        val bbox = italianRegions[regionName]
            ?: throw IllegalArgumentException("Regione non trovata: $regionName")

        val midLat = (bbox.minLat + bbox.maxLat) / 2.0
        val midLon = (bbox.minLon + bbox.maxLon) / 2.0

        val quads = listOf(
            BoundingBox(bbox.minLat, bbox.minLon, midLat, midLon),
            BoundingBox(bbox.minLat, midLon, midLat, bbox.maxLon),
            BoundingBox(midLat, bbox.minLon, bbox.maxLat, midLon),
            BoundingBox(midLat, midLon, bbox.maxLat, bbox.maxLon)
        )

        val allLines = mutableSetOf<String>()
        for (q in quads) {
            try {
                val lines = fetchOverpassLinesForBBox(q.minLat, q.minLon, q.maxLat, q.maxLon)
                allLines.addAll(lines)
            } catch (e: Exception) {
                Log.w("HybridMapRepository", "Error downloading quadrant for $regionName: ${e.message}")
            }
        }

        val sanitized = regionName.lowercase(Locale.ROOT).replace("[^a-z0-9]".toRegex(), "_")
        val file = File(mapsDir, "regione_${sanitized}.motomap")
        file.writeText(allLines.joinToString("\n"))
        return file
    }

    private fun String?.isNull_or_blank(): Boolean {
        return this == null || this.trim().isEmpty()
    }
}
