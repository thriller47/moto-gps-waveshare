package com.motogps.android.viewmodel

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlin.math.cos

/**
 * Nearby OSM POIs indexed from the Italy PBF into the existing Photon v2 SQLite.
 * Older Photon v2 indexes do not have poi_places: caller then uses the normal
 * Photon search without pretending a name search is an exhaustive category scan.
 */
internal object OfflinePoiCategorySearch {
    private val synonyms = mapOf(
        "distributore" to listOf("fuel"),
        "distributori" to listOf("fuel"),
        "benzinaio" to listOf("fuel"),
        "benzinai" to listOf("fuel"),
        "stazione di servizio" to listOf("fuel"),
        "stazioni di servizio" to listOf("fuel"),
        "ristorante" to listOf("restaurant"),
        "ristoranti" to listOf("restaurant"),
        "trattoria" to listOf("restaurant"),
        "trattorie" to listOf("restaurant"),
        "osteria" to listOf("restaurant"),
        "osterie" to listOf("restaurant"),
        "bar" to listOf("bar", "cafe"),
        "caffe" to listOf("cafe", "bar"),
        "caffetteria" to listOf("cafe", "bar"),
        "caffetterie" to listOf("cafe", "bar"),
        "pub" to listOf("bar"),
        "albergo" to listOf("hotel"),
        "alberghi" to listOf("hotel"),
        "hotel" to listOf("hotel"),
        "motel" to listOf("hotel"),
        "pensione" to listOf("hotel"),
        "pensioni" to listOf("hotel"),
        "affittacamere" to listOf("hotel"),
        "ostello" to listOf("hotel"),
        "ostelli" to listOf("hotel")
    )
    fun categories(query: String): List<String> =
        synonyms[PhotonOfflineRanking.normalize(query)].orEmpty()

    fun available(context: Context): Boolean {
        val file = OfflinePhotonPlaceSearch.file(context)
        if (!file.isFile) return false
        return try {
            SQLiteDatabase.openDatabase(file.absolutePath, null,
                SQLiteDatabase.OPEN_READONLY).use { db ->
                db.version == 2 && db.rawQuery(
                    "SELECT 1 FROM sqlite_master WHERE type='table' AND name='poi_places'",
                    null).use { it.moveToFirst() }
            }
        } catch (_: Exception) { false }
    }

    fun search(context: Context, query: String,
               gps: Pair<Double, Double>?): List<PlaceSearchResult> {
        val kinds = categories(query)
        if (kinds.isEmpty()) return emptyList()
        val (lat, lon) = gps?.takeIf {
            it.first.isFinite() && it.second.isFinite() &&
                it.first in -85.0..85.0 && it.second in -180.0..180.0
        } ?: return emptyList() // A nearby search must have a valid position.
        val file = OfflinePhotonPlaceSearch.file(context)
        if (!file.isFile) return emptyList()
        val placeholders = kinds.joinToString(",") { "?" }
        // Bounding-box filter uses poi_category_lat_lon; never full-table scans.
        // Broaden only if the immediate area has fewer than eight results.
        val sql = """
            SELECT name,subtype,lat,lon
              FROM poi_places
             WHERE category IN ($placeholders)
               AND lat BETWEEN ? AND ?
               AND lon BETWEEN ? AND ?
             ORDER BY (lat-?)*(lat-?) +
                      (lon-?)*(lon-?)*?*?
             LIMIT 32
        """.trimIndent()
        val result = ArrayList<PlaceSearchResult>(8)
        SQLiteDatabase.openDatabase(file.absolutePath, null,
            SQLiteDatabase.OPEN_READONLY).use { db ->
            for (radiusKm in listOf(15.0, 50.0, 150.0)) {
                val deltaLat = radiusKm / 111.2
                val lonScale = cos(Math.toRadians(lat)).coerceAtLeast(0.05)
                val deltaLon = radiusKm / (111.2 * lonScale)
                val values = kinds + listOf(
                    (lat - deltaLat).toString(), (lat + deltaLat).toString(),
                    (lon - deltaLon).toString(), (lon + deltaLon).toString(),
                    lat.toString(), lat.toString(), lon.toString(), lon.toString(),
                    lonScale.toString(), lonScale.toString()
                )
                result.clear()
                db.rawQuery(sql, values.toTypedArray()).use { cursor ->
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(0)
                        val subtype = cursor.getString(1)
                        val poiLat = cursor.getDouble(2)
                        val poiLon = cursor.getDouble(3)
                        result.add(PlaceSearchResult(
                            name, "", subtype, poiLat, poiLon))
                    }
                }
                if (result.size >= 8) break
            }
        }
        return result.take(8)
    }
}
