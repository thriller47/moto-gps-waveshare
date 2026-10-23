package com.motogps.android.map

import android.content.Context
import android.util.Log
import com.motogps.android.jni.GeoPointE6
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.delay
import org.json.JSONObject

data class SavedMapInfo(
    val fileName: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long
)

data class DownloadResult(
    val file: File,
    val totalSegments: Int
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

    companion object {
        fun getSharedMapsDir(context: Context): File {
            val dir = File(context.filesDir, "maps")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            return dir
        }
    }

    private val mirrors = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter"
    )

    private val mapsDir: File by lazy {
        getSharedMapsDir(context)
    }

    fun listSavedMaps(): List<SavedMapInfo> {
        val files = mapsDir.listFiles { _, name -> name.endsWith(".motomap") || name.endsWith(".json") } ?: return emptyList()
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

    // Online context is fetched in small, reusable tiles. No synthetic geometry
    // is written when an Overpass server is unavailable.
    fun ensureOnlineTile(lat: Double, lon: Double): Boolean {
        val step = 0.02
        val latTile = kotlin.math.floor(lat / step).toInt()
        val lonTile = kotlin.math.floor(lon / step).toInt()
        val file = File(mapsDir, "online_tile_${latTile}_${lonTile}.motomap")
        if (file.exists() && file.length() > 0L &&
            System.currentTimeMillis() - file.lastModified() < 24L * 60 * 60 * 1000) {
            return true
        }
        val south = latTile * step
        val west = lonTile * step
        val query = String.format(
            Locale.US,
            "[out:json][timeout:25];way[\\"highway\\"~\\"^(primary|primary_link|secondary|secondary_link|tertiary|tertiary_link|residential|unclassified)$\\"](%.6f,%.6f,%.6f,%.6f);out body geom qt;",
            south, west, south + step, west + step
        )
        val temp = File(mapsDir, file.name + ".tmp")
        return try {
            val count = temp.bufferedWriter().use { writer ->
                val result = streamOverpassQueryToWriter(query, writer)
                writer.flush()
                result
            }
            if (count == 0) {
                temp.delete()
                false
            } else {
                if (file.exists()) file.delete()
                temp.renameTo(file)
            }
        } catch (e: Exception) {
            temp.delete()
            Log.w("HybridMapRepository", "Online context tile unavailable: ${e.message}")
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

    fun streamOverpassToMotomap(inputStream: InputStream, writer: BufferedWriter): Int {
        var totalWritten = 0
        val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8), 8192)

        val searchStr = "\"elements\""
        var matchIndex = 0
        var foundArrayStart = false
        var chInt: Int

        while (reader.read().also { chInt = it } != -1) {
            val c = chInt.toChar()
            if (c == searchStr[matchIndex]) {
                matchIndex++
                if (matchIndex == searchStr.length) {
                    while (reader.read().also { chInt = it } != -1) {
                        if (chInt.toChar() == '[') {
                            foundArrayStart = true
                            break
                        }
                    }
                    break
                }
            } else {
                matchIndex = if (c == searchStr[0]) 1 else 0
            }
        }

        if (!foundArrayStart) return 0

        var depth = 0
        var inString = false
        var isEscaped = false
        val sb = StringBuilder()

        while (reader.read().also { chInt = it } != -1) {
            val c = chInt.toChar()

            if (depth > 0) {
                sb.append(c)
            }

            if (isEscaped) {
                isEscaped = false
                continue
            }

            if (c == '\\' && inString) {
                isEscaped = true
                continue
            }

            if (c == '"') {
                inString = !inString
                continue
            }

            if (!inString) {
                if (c == '{') {
                    if (depth == 0) {
                        sb.setLength(0)
                        sb.append(c)
                    }
                    depth++
                } else if (c == '}') {
                    if (depth > 0) {
                        depth--
                        if (depth == 0) {
                            val elementJsonStr = sb.toString()
                            totalWritten += parseAndWriteElementJson(elementJsonStr, writer)
                            sb.setLength(0)
                        }
                    }
                } else if (c == ']' && depth == 0) {
                    break
                }
            }
        }

        return totalWritten
    }

    private fun parseAndWriteElementJson(jsonStr: String, writer: BufferedWriter): Int {
        try {
            val elem = JSONObject(jsonStr)
            val type = elem.optString("type")
            if (type != "way") return 0

            val tags = elem.optJSONObject("tags") ?: return 0
            val highway = tags.optString("highway")
            if (highway.isBlank()) return 0

            val geomArray = elem.optJSONArray("geometry") ?: return 0
            val wayPoints = mutableListOf<Pair<Double, Double>>()

            for (i in 0 until geomArray.length()) {
                val pt = geomArray.optJSONObject(i) ?: continue
                val lat = pt.optDouble("lat", Double.NaN)
                val lon = pt.optDouble("lon", Double.NaN)
                if (!lat.isNaN() && !lon.isNaN()) {
                    wayPoints.add(Pair(lat, lon))
                }
            }

            if (wayPoints.size < 2) return 0

            val maxspeed = tags.optString("maxspeed", null)
            return processAndWriteWay(highway, maxspeed, wayPoints, writer)
        } catch (_: Exception) {
            return 0
        }
    }

    fun writeFallbackSegment(
        writer: BufferedWriter,
        lat: Double,
        lon: Double,
        rawRoutePoints: List<Pair<Double, Double>> = emptyList()
    ): Int {
        val pts = if (rawRoutePoints.size >= 2) {
            rawRoutePoints
        } else {
            listOf(
                Pair(lat - 0.001, lon - 0.001),
                Pair(lat + 0.001, lon + 0.001)
            )
        }

        var minL = Double.MAX_VALUE
        var maxL = -Double.MAX_VALUE
        var minO = Double.MAX_VALUE
        var maxO = -Double.MAX_VALUE

        for (pt in pts) {
            if (pt.first < minL) minL = pt.first
            if (pt.first > maxL) maxL = pt.first
            if (pt.second < minO) minO = pt.second
            if (pt.second > maxO) maxO = pt.second
        }

        val minLatE6 = (minL * 1e6).toInt()
        val minLonE6 = (minO * 1e6).toInt()
        val maxLatE6 = (maxL * 1e6).toInt()
        val maxLonE6 = (maxO * 1e6).toInt()

        val ptsStr = pts.joinToString(";") { String.format(Locale.US, "%.6f,%.6f", it.first, it.second) }
        writer.write("3,50|$minLatE6,$minLonE6,$maxLatE6,$maxLonE6|$ptsStr\n")
        return 1
    }

    private fun processAndWriteWay(
        highway: String,
        maxspeed: String?,
        rawPoints: List<Pair<Double, Double>>,
        writer: BufferedWriter
    ): Int {
        var points = rawPoints
        if (points.size > 60) {
            val simplified = mutableListOf<Pair<Double, Double>>()
            for (i in points.indices) {
                if (i == 0 || i == points.size - 1 || i % 2 == 0) {
                    simplified.add(points[i])
                }
            }
            points = simplified
        }

        val chunks = mutableListOf<List<Pair<Double, Double>>>()
        if (points.size > 40) {
            var start = 0
            while (start < points.size - 1) {
                val end = (start + 39).coerceAtMost(points.size - 1)
                val sub = points.subList(start, end + 1)
                if (sub.size >= 2) {
                    chunks.add(sub)
                }
                if (end == points.size - 1) break
                start = end
            }
        } else {
            chunks.add(points)
        }

        val roadClass = parseHighwayClass(highway)
        val maxSpeedKph = parseMaxSpeedTag(maxspeed, roadClass)

        var count = 0
        for (chunk in chunks) {
            if (chunk.size < 2) continue
            var minL = Double.MAX_VALUE
            var maxL = -Double.MAX_VALUE
            var minO = Double.MAX_VALUE
            var maxO = -Double.MAX_VALUE

            for (pt in chunk) {
                if (pt.first < minL) minL = pt.first
                if (pt.first > maxL) maxL = pt.first
                if (pt.second < minO) minO = pt.second
                if (pt.second > maxO) maxO = pt.second
            }

            val minLatE6 = (minL * 1e6).toInt()
            val minLonE6 = (minO * 1e6).toInt()
            val maxLatE6 = (maxL * 1e6).toInt()
            val maxLonE6 = (maxO * 1e6).toInt()

            val ptsStr = chunk.joinToString(";") { pt ->
                String.format(Locale.US, "%.6f,%.6f", pt.first, pt.second)
            }

            writer.write("$roadClass,$maxSpeedKph|$minLatE6,$minLonE6,$maxLatE6,$maxLonE6|$ptsStr\n")
            count++
        }
        return count
    }

    fun streamOverpassQueryToWriter(query: String, writer: BufferedWriter): Int {
        var lastException: Exception? = null
        val postData = "data=" + URLEncoder.encode(query, "UTF-8")

        for (endpointUrl in mirrors) {
            var conn: HttpURLConnection? = null
            try {
                val url = URL(endpointUrl)
                conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.setRequestProperty("Accept-Encoding", "gzip")
                conn.connectTimeout = 10000
                conn.readTimeout = 30000

                conn.outputStream.use { os ->
                    os.write(postData.toByteArray(Charsets.UTF_8))
                }

                val responseCode = conn.responseCode
                if (responseCode in 200..299) {
                    val inputStream = if ("gzip".equals(conn.contentEncoding, ignoreCase = true)) {
                        GZIPInputStream(conn.inputStream)
                    } else {
                        conn.inputStream
                    }
                    return streamOverpassToMotomap(inputStream, writer)
                } else {
                    Log.w("HybridMapRepository", "Mirror $endpointUrl returned HTTP $responseCode")
                }
            } catch (e: Exception) {
                Log.w("HybridMapRepository", "Mirror $endpointUrl failed: ${e.message}")
                lastException = e
            } finally {
                conn?.disconnect()
            }
        }
        throw lastException ?: Exception("All Overpass mirrors failed")
    }

    fun buildQuadrantsGrid(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double, stepDeg: Double): List<BoundingBox> {
        val quads = mutableListOf<BoundingBox>()
        var curLat = minLat
        while (curLat < maxLat) {
            val nextLat = (curLat + stepDeg).coerceAtMost(maxLat)
            var curLon = minLon
            while (curLon < maxLon) {
                val nextLon = (curLon + stepDeg).coerceAtMost(maxLon)
                quads.add(BoundingBox(curLat, curLon, nextLat, nextLon))
                curLon = nextLon
            }
            curLat = nextLat
        }
        return quads
    }

    suspend fun downloadCurrentArea(
        lat: Double,
        lon: Double,
        onProgress: ((Int, Int, String) -> Unit)? = null
    ): DownloadResult {
        val delta = 0.45
        val minLat = lat - delta
        val maxLat = lat + delta
        val minLon = lon - delta
        val maxLon = lon + delta

        val quads = buildQuadrantsGrid(minLat, minLon, maxLat, maxLon, 0.30)
        val targetFile = File(mapsDir, "area_corrente_50km.motomap")
        if (targetFile.exists()) targetFile.delete()

        var totalSegments = 0
        val fos = java.io.FileOutputStream(targetFile, true)
        val osw = OutputStreamWriter(fos, Charsets.UTF_8)
        val writer = BufferedWriter(osw)

        try {
            for ((index, q) in quads.withIndex()) {
                if (index > 0) delay(800)
                val query = String.format(
                    Locale.US,
                    "[out:json][timeout:25];(way[\"highway\"~\"^(primary|primary_link|secondary|secondary_link|tertiary|tertiary_link|residential|unclassified)$\"](%.6f,%.6f,%.6f,%.6f););out body geom qt;",
                    q.minLat, q.minLon, q.maxLat, q.maxLon
                )
                try {
                    val written = streamOverpassQueryToWriter(query, writer)
                    totalSegments += written
                    writer.flush()
                } catch (e: Exception) {
                    Log.w("HybridMapRepository", "Quadrant $index failed for 50km area: ${e.message}")
                }
                onProgress?.invoke(index + 1, quads.size, "Area Attuale 50km")
            }

            if (totalSegments == 0) {
                totalSegments += writeFallbackSegment(writer, lat, lon)
                writer.flush()
            }
            fos.fd.sync()
        } finally {
            try { writer.close() } catch (_: Exception) {}
        }
        return DownloadResult(targetFile, totalSegments)
    }

    suspend fun downloadRouteCorridor(
        rawRoutePoints: List<Pair<Double, Double>>,
        onProgress: ((Int, Int, String) -> Unit)? = null
    ): DownloadResult {
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
            if (accumulatedDist >= 5.0) {
                sampledPoints.add(p2)
                accumulatedDist = 0.0
            }
        }
        if (sampledPoints.last() != rawRoutePoints.last()) {
            sampledPoints.add(rawRoutePoints.last())
        }

        val targetFile = File(mapsDir, "corridoio_rotta.motomap")
        if (targetFile.exists()) targetFile.delete()

        var totalSegments = 0
        val fos = java.io.FileOutputStream(targetFile, true)
        val osw = OutputStreamWriter(fos, Charsets.UTF_8)
        val writer = BufferedWriter(osw)

        try {
            for ((index, pt) in sampledPoints.withIndex()) {
                if (index > 0) delay(800)
                val lat = pt.first
                val lon = pt.second
                val latDelta = 5.0 / 111.0
                val lonDelta = 5.0 / (111.0 * Math.cos(Math.toRadians(lat)).coerceAtLeast(0.1))

                val query = String.format(
                    Locale.US,
                    "[out:json][timeout:25];(way[\"highway\"](%.6f,%.6f,%.6f,%.6f););out body geom qt;",
                    lat - latDelta, lon - lonDelta, lat + latDelta, lon + lonDelta
                )
                try {
                    val written = streamOverpassQueryToWriter(query, writer)
                    totalSegments += written
                    writer.flush()
                } catch (e: Exception) {
                    Log.w("HybridMapRepository", "Corridor point $index failed: ${e.message}")
                }
                onProgress?.invoke(index + 1, sampledPoints.size, "Corridoio Rotta")
            }

            if (totalSegments == 0) {
                totalSegments += writeFallbackSegment(writer, rawRoutePoints.first().first, rawRoutePoints.first().second, rawRoutePoints)
                writer.flush()
            }
            fos.fd.sync()
        } finally {
            try { writer.close() } catch (_: Exception) {}
        }
        return DownloadResult(targetFile, totalSegments)
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

    suspend fun downloadRegion(
        regionName: String,
        onProgress: ((Int, Int, String) -> Unit)? = null
    ): DownloadResult {
        val bbox = italianRegions[regionName]
            ?: throw IllegalArgumentException("Regione non trovata: $regionName")

        val quads = buildQuadrantsGrid(bbox.minLat, bbox.minLon, bbox.maxLat, bbox.maxLon, 0.40)
        val sanitized = regionName.lowercase(Locale.ROOT).replace("[^a-z0-9]".toRegex(), "_")
        val targetFile = File(mapsDir, "regione_${sanitized}.motomap")
        if (targetFile.exists()) targetFile.delete()

        var totalSegments = 0
        val fos = java.io.FileOutputStream(targetFile, true)
        val osw = OutputStreamWriter(fos, Charsets.UTF_8)
        val writer = BufferedWriter(osw)

        try {
            for ((index, q) in quads.withIndex()) {
                if (index > 0) delay(800)
                val query = String.format(
                    Locale.US,
                    "[out:json][timeout:25];(way[\"highway\"~\"^(primary|primary_link|secondary|secondary_link|tertiary|tertiary_link|residential|unclassified)$\"](%.6f,%.6f,%.6f,%.6f););out body geom qt;",
                    q.minLat, q.minLon, q.maxLat, q.maxLon
                )
                try {
                    val written = streamOverpassQueryToWriter(query, writer)
                    totalSegments += written
                    writer.flush()
                } catch (e: Exception) {
                    Log.w("HybridMapRepository", "Quadrant $index failed for $regionName: ${e.message}")
                }
                onProgress?.invoke(index + 1, quads.size, regionName)
            }

            if (totalSegments == 0) {
                val centerLat = (bbox.minLat + bbox.maxLat) / 2.0
                val centerLon = (bbox.minLon + bbox.maxLon) / 2.0
                totalSegments += writeFallbackSegment(writer, centerLat, centerLon)
                writer.flush()
            }
            fos.fd.sync()
        } finally {
            try { writer.close() } catch (_: Exception) {}
        }
        return DownloadResult(targetFile, totalSegments)
    }

    private fun String?.isNull_or_blank(): Boolean {
        return this == null || this.trim().isEmpty()
    }
}
