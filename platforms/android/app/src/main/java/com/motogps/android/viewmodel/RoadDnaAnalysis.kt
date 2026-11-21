package com.motogps.android.viewmodel

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Descriptive offline POI candidates along an ACTUAL Valhalla route.
 * Bounding boxes are approximate for areas; this never suggests access roads.
 */
data class RoadDnaPoi(
    val name: String, val category: String, val approximateArea: Boolean,
    val distanceMeters: Int
)
data class RoadDnaSummary(
    val routeId: String,
    val curvature: String,
    val countByCategory: Map<String, Int>,
    val examples: List<RoadDnaPoi>,
    val indexAvailable: Boolean,
    val routeVertices: Int
) {
    val totalByCategory: Int get() = countByCategory.values.sum()
}

internal object RoadDnaAnalysis {
    private const val RADIUS_M = 2000.0
    private const val CELL = 0.05

    // JSON coordinates of the existing route adapter are [lon,lat] arrays or
    // latitude_deg/longitude_deg objects. Never infer a straight-line route.
    internal fun geometry(json: JSONObject): List<Pair<Double, Double>> {
        val array = json.optJSONArray("geometry_gcj02")
            ?: json.optJSONArray("polyline")
            ?: json.optJSONArray("coordinates")
            ?: return emptyList()
        val result = ArrayList<Pair<Double, Double>>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i)
            val lat: Double
            val lon: Double
            if (obj != null) {
                lat = obj.optDouble("latitude_deg", obj.optDouble("latitude", Double.NaN))
                lon = obj.optDouble("longitude_deg", obj.optDouble("longitude", Double.NaN))
            } else {
                val point = array.optJSONArray(i) ?: continue
                lat = point.optDouble(1, Double.NaN)
                lon = point.optDouble(0, Double.NaN)
            }
            if (!lat.isFinite() || !lon.isFinite() ||
                lat !in -90.0..90.0 || lon !in -180.0..180.0) continue
            val current = lat to lon
            if (result.lastOrNull() != current) result.add(current)
        }
        return result
    }

    internal fun distanceMeters(p: Pair<Double, Double>,
                                a: Pair<Double, Double>,
                                b: Pair<Double, Double>): Double {
        val cosLat = cos(Math.toRadians(p.first)).coerceAtLeast(0.02)
        val x1 = (a.second - p.second) * 111195.0 * cosLat
        val y1 = (a.first - p.first) * 111195.0
        val x2 = (b.second - p.second) * 111195.0 * cosLat
        val y2 = (b.first - p.first) * 111195.0
        val dx = x2 - x1
        val dy = y2 - y1
        val lengthSq = dx * dx + dy * dy
        val t = if (lengthSq > 0) (-(x1 * dx + y1 * dy) / lengthSq)
            .coerceIn(0.0, 1.0) else 0.0
        val x = x1 + t * dx
        val y = y1 + t * dy
        return sqrt(x * x + y * y)
    }

    private fun key(latCell: Int, lonCell: Int): Long =
        (latCell.toLong() shl 32) xor (lonCell.toLong() and 0xffffffffL)

    private class RouteGrid(val points: List<Pair<Double, Double>>) {
        val index = HashMap<Long, MutableList<Int>>()
        val latMin = points.minOf { it.first }
        val latMax = points.maxOf { it.first }
        val lonMin = points.minOf { it.second }
        val lonMax = points.maxOf { it.second }
        private val lonPadding = RADIUS_M /
            (111195.0 * cos(Math.toRadians((latMin + latMax) / 2.0)).coerceAtLeast(0.1))
        private val latPadding = RADIUS_M / 111195.0

        init {
            for (i in 1 until points.size) {
                val a = points[i-1]
                val b = points[i]
                // Guard against corrupted geometry that would fill every grid cell.
                if (abs(a.first - b.first) > 0.7 || abs(a.second - b.second) > 0.7)
                    continue
                val la = floor((min(a.first,b.first)-latPadding)/CELL).toInt()
                val lb = floor((max(a.first,b.first)+latPadding)/CELL).toInt()
                val oa = floor((min(a.second,b.second)-lonPadding)/CELL).toInt()
                val ob = floor((max(a.second,b.second)+lonPadding)/CELL).toInt()
                for (x in la..lb) for (y in oa..ob)
                    index.getOrPut(key(x,y)) { ArrayList() }.add(i)
            }
        }

        fun candidateSegments(minLat: Double, maxLat: Double,
                              minLon: Double, maxLon: Double): Set<Int> {
            val result = HashSet<Int>()
            val la = floor(minLat/CELL).toInt()
            val lb = floor(maxLat/CELL).toInt()
            val oa = floor(minLon/CELL).toInt()
            val ob = floor(maxLon/CELL).toInt()
            if ((lb.toLong()-la+1) * (ob.toLong()-oa+1) > 5000L) {
                // Large protected areas: compare all route segments; do not
                // silently discard a lake whose centroid is far from the road.
                return (1 until points.size).toSet()
            }
            for (x in la..lb) for (y in oa..ob)
                index[key(x,y)]?.let { result.addAll(it) }
            return result
        }

        fun nearest(lat: Double, lon: Double): Double {
            var best = Double.POSITIVE_INFINITY
            for (i in candidateSegments(lat, lat, lon, lon)) {
                val distance = distanceMeters(lat to lon, points[i-1], points[i])
                if (distance < best) best = distance
            }
            return best
        }

        fun boundsNear(minLat: Double,maxLat: Double,
                       minLon: Double,maxLon: Double): Boolean {
            val expandedLat = RADIUS_M / 111195.0
            val expandedLon = lonPadding
            for (i in candidateSegments(minLat, maxLat, minLon, maxLon)) {
                val a = points[i-1]
                val b = points[i]
                if (max(a.first,b.first) + expandedLat >= minLat &&
                    min(a.first,b.first) - expandedLat <= maxLat &&
                    max(a.second,b.second) + expandedLon >= minLon &&
                    min(a.second,b.second) - expandedLon <= maxLon) return true
            }
            return false
        }

        fun bounds(): DoubleArray = doubleArrayOf(
            latMin-latPadding, latMax+latPadding,
            lonMin-lonPadding, lonMax+lonPadding)
    }

    internal fun curves(points: List<Pair<Double,Double>>): String {
        if (points.size < 3) return "Dati insufficienti"
        var corners = 0
        var kilometers = 0.0
        var last = points.first()
        val sampled = ArrayList<Pair<Double,Double>>()
        sampled.add(last)
        for (p in points.drop(1)) {
            val distance = distanceMeters(p, last, last)
            if (distance >= 55.0) {
                kilometers += distance / 1000.0
                sampled.add(p)
                last = p
            }
        }
        for (i in 1 until sampled.lastIndex) {
            val a = sampled[i-1]
            val b = sampled[i]
            val c = sampled[i+1]
            val cx = cos(b.first * PI/180.0)
            val x1 = (b.second-a.second)*cx
            val y1 = b.first-a.first
            val x2 = (c.second-b.second)*cx
            val y2 = c.first-b.first
            val denom = sqrt((x1*x1+y1*y1)*(x2*x2+y2*y2))
            if (denom < 1e-12) continue
            val angle = acos(((x1*x2+y1*y2)/denom).coerceIn(-1.0,1.0))*180.0/PI
            if (angle in 15.0..145.0) corners++
        }
        if (kilometers <= 0.1) return "Dati insufficienti"
        return when {
            corners / kilometers < 0.8 -> "Bassa (indicativa)"
            corners / kilometers < 2.5 -> "Media (indicativa)"
            else -> "Alta (indicativa)"
        }
    }

    fun analyze(context: Context, option: RouteOption,
                cancelled: CancellationSignal? = null): RoadDnaSummary {
        val points = geometry(option.rawRouteJson)
        if (points.size < 2) throw IllegalArgumentException("Geometria Valhalla assente")
        val curvature = curves(points)
        val file = OfflineTouristPlaceSearch.file(context)
        if (!file.isFile) return RoadDnaSummary(
            option.id, curvature, emptyMap(), emptyList(), false, points.size)
        val grid = RouteGrid(points)
        val b = grid.bounds()
        val counts = HashMap<String, Int>()
        val named = ArrayList<RoadDnaPoi>()
        SQLiteDatabase.openDatabase(file.absolutePath, null,
            SQLiteDatabase.OPEN_READONLY).use { db ->
            // One SQL pass per button click, not many expensive corridor bbox
            // queries. Final segment distance removes most off-route matches.
            db.rawQuery("""
                SELECT category,name,lat,lon,min_lat,max_lat,min_lon,max_lon,geometry_type
                  FROM tourist_poi
                 WHERE max_lat >= ? AND min_lat <= ?
                   AND max_lon >= ? AND min_lon <= ?
            """.trimIndent(), arrayOf(
                b[0].toString(), b[1].toString(),
                b[2].toString(), b[3].toString()), cancelled).use { rows ->
                while (rows.moveToNext()) {
                    val category = rows.getString(0)
                    val name = rows.getString(1).orEmpty()
                    val lat = rows.getDouble(2)
                    val lon = rows.getDouble(3)
                    val minLat = rows.getDouble(4)
                    val maxLat = rows.getDouble(5)
                    val minLon = rows.getDouble(6)
                    val maxLon = rows.getDouble(7)
                    val area = rows.getString(8) == "area"
                    val distance = grid.nearest(lat,lon)
                    val candidate = if (area)
                        grid.boundsNear(minLat,maxLat,minLon,maxLon)
                    else distance <= RADIUS_M
                    if (!candidate) continue
                    counts[category] = (counts[category] ?: 0) + 1
                    if (name.isNotBlank()) {
                        // Keep only a bounded set for the screen, sorted later.
                        val estimated = if (area && distance > RADIUS_M) 0
                            else distance.toInt().coerceAtLeast(0)
                        named.add(RoadDnaPoi(name, category, area, estimated))
                    }
                }
            }
        }
        val examples = named.sortedWith(compareBy<RoadDnaPoi> { it.distanceMeters }
            .thenBy { it.name }).distinctBy { it.category + "|" + it.name }
            .take(35)
        return RoadDnaSummary(option.id, curvature, counts.toSortedMap(),
                              examples, true, points.size)
    }
}
