package com.motogps.android.viewmodel

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.text.Normalizer
import java.util.Locale
import kotlin.math.cos

/** SQLite FTS4 index generated from the Photon/Nominatim Italian JSONL dump. */
internal object OfflinePhotonPlaceSearch {
    private const val DATABASE = "photon-italy.sqlite"
    private const val MAX_BYTES = 6L * 1024L * 1024L * 1024L
    private const val VERSION = 2
    fun file(context: Context): File = File(context.filesDir, "photon/" + DATABASE)
    fun installed(context: Context): Boolean {
        val target = file(context)
        if (!target.isFile || target.length() == 0L) return false
        return try {
            SQLiteDatabase.openDatabase(target.absolutePath, null,
                SQLiteDatabase.OPEN_READONLY).use { it.version == VERSION }
        } catch (_: Exception) {
            false
        }
    }

    fun tokenize(query: String): String = PhotonOfflineRanking.words(query)
        .take(8).joinToString(" ") { it.take(32) + "*" }

    /** Store an imported index atomically; never touch MotoMap or Valhalla files. */
    fun install(context: Context, uri: Uri, progress: (Long) -> Unit = {}): Long {
        val target = file(context)
        val folder = target.parentFile ?: throw IOException("Cartella Photon assente")
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Impossibile creare cartella Photon")
        val part = File(folder, DATABASE + ".part")
        val backup = File(folder, DATABASE + ".backup")
        if (part.exists() && !part.delete()) throw IOException("Copia Photon precedente occupata")
        try {
            val size = if (uri.scheme == "file") {
                uri.path?.let { File(it).length() } ?: -1L
            } else {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE),
                    null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
                } ?: -1L
            }
            if (size > MAX_BYTES) throw IOException("Indice Photon troppo grande")
            if (size > 0 && folder.usableSpace < size + 64L * 1024 * 1024)
                throw IOException("Spazio libero insufficiente per importare Photon")
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Impossibile leggere il file Photon")
            input.use { source ->
                part.outputStream().buffered().use { dest ->
                    val bytes = ByteArray(128 * 1024)
                    var copied = 0L
                    var lastReported = 0L
                    while (true) {
                        val n = source.read(bytes)
                        if (n < 0) break
                        copied += n
                        if (copied > MAX_BYTES) throw IOException("Indice Photon troppo grande")
                        dest.write(bytes, 0, n)
                        if (copied - lastReported >= 64L * 1024 * 1024) {
                            progress(copied)
                            lastReported = copied
                        }
                    }
                }
            }
            if (part.length() < 16384L) throw IOException("File Photon vuoto")
            SQLiteDatabase.openDatabase(part.absolutePath, null,
                SQLiteDatabase.OPEN_READONLY).use { db ->
                if (db.version != VERSION) throw IOException("Schema Photon incompatibile")
                db.rawQuery("SELECT value FROM metadata WHERE key='source'", null).use { rows ->
                    if (!rows.moveToFirst() || rows.getString(0) != "photon-json-dump-0.1.0")
                        throw IOException("Questo SQLite non proviene dal convertitore Photon")
                }
                db.rawQuery("SELECT value FROM metadata WHERE key='places'", null).use { rows ->
                    if (!rows.moveToFirst() || (rows.getString(0).toLongOrNull() ?: 0L) <= 0L)
                        throw IOException("Indice Photon senza luoghi")
                }
                db.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' " +
                        "AND name IN ('places_fts','cities','regions')", null).use { rows ->
                    if (rows.count != 3) throw IOException("Struttura Photon v2 incompleta")
                }
                db.rawQuery("SELECT COUNT(*) FROM places", null).use { rows ->
                    if (!rows.moveToFirst() || rows.getLong(0) <= 0L)
                        throw IOException("Nessun luogo indicizzato")
                }
            }
            if (backup.exists() && !backup.delete()) throw IOException("Backup Photon occupato")
            if (target.exists() && !target.renameTo(backup))
                throw IOException("Impossibile proteggere il vecchio indice")
            if (!part.renameTo(target)) {
                if (backup.exists()) backup.renameTo(target)
                throw IOException("Impossibile attivare il nuovo indice Photon")
            }
            if (backup.exists()) backup.delete()
            return target.length()
        } finally {
            if (part.exists()) part.delete()
        }
    }

    fun delete(context: Context): Boolean = file(context).let { !it.exists() || it.delete() }

    /**
     * Generic fuel search across branded stations already present in the Photon v2
     * FTS4 index. No new schema, no public-server fan-out and no MotoPack rebuild.
     * FTS has name tokens, not a dedicated amenity lookup: unbranded fuel POIs
     * without a searchable fuel name remain a known limitation.
     */
    private fun searchFuelStations(
        context: Context, gps: Pair<Double, Double>?
    ): List<PlaceSearchResult> {
        val target = file(context)
        val validGps = gps?.takeIf {
            it.first.isFinite() && it.second.isFinite() &&
                it.first in -90.0..90.0 && it.second in -180.0..180.0
        }
        val lat = validGps?.first ?: 0.0
        val lon = validGps?.second ?: 0.0
        val scale = cos(Math.toRadians(lat))
        // Select by exact indexed brand tokens, and filter by actual station name/kind
        // after reading the bounded nearby shortlist. Avoid scanning millions
        // of rows from the unindexed places.kind column on existing v2 packs.
        val sql = """
            SELECT p.name,p.street,p.house,c.name,r.name,p.postcode,
                   p.kind,p.importance,p.lat,p.lon
              FROM places_fts JOIN places p ON p.id = places_fts.docid
              JOIN cities c ON c.id=p.city_id
              JOIN regions r ON r.id=p.region_id
             WHERE places_fts MATCH ?
             ORDER BY CASE WHEN ? = '1' THEN
                          (p.lat - ?) * (p.lat - ?) +
                          (p.lon - ?) * (p.lon - ?) * ? * ?
                      ELSE 0 END,
                      p.importance DESC
             LIMIT 4000
        """.trimIndent()
        val candidates = ArrayList<PhotonOfflineRanking.Candidate>()
        SQLiteDatabase.openDatabase(target.absolutePath, null,
            SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(sql, arrayOf(
                FuelStationSearch.ftsQuery,
                if (validGps != null) "1" else "0",
                lat.toString(), lat.toString(), lon.toString(), lon.toString(),
                scale.toString(), scale.toString())).use { rows ->
                while (rows.moveToNext()) {
                    val candidate = PhotonOfflineRanking.Candidate(
                        rows.getString(0), rows.getString(1), rows.getString(2),
                        rows.getString(3), rows.getString(4), rows.getString(5),
                        rows.getString(6), rows.getDouble(7), rows.getDouble(8),
                        rows.getDouble(9))
                    if (FuelStationSearch.isStation(candidate.name, candidate.kind)) {
                        candidates.add(candidate)
                    }
                }
            }
        }
        return PhotonOfflineRanking.rank(candidates, "distributore", validGps)
            .map { place ->
                val address = listOf(place.street +
                    (if (place.house.isNotBlank()) " " + place.house else ""),
                    place.postcode, place.city)
                    .filter { it.isNotBlank() }.distinct().joinToString(", ")
                val area = listOf(place.city, place.region, "Italia")
                    .filter { it.isNotBlank() }.distinct().joinToString(", ")
                PlaceSearchResult(place.name, address, area, place.lat, place.lon)
            }
    }

    /** An installed index always takes priority over the remote Photon service. */
    fun search(context: Context, query: String, gps: Pair<Double, Double>?): List<PlaceSearchResult> {
        val target = file(context)
        if (!target.isFile) throw IOException("Indice Photon non installato")
        if (FuelStationSearch.isGenericQuery(query)) {
            return searchFuelStations(context, gps)
        }
        val match = tokenize(query)
        if (match.isBlank()) return emptyList()
        val normalized = PhotonOfflineRanking.normalize(query)
        val cityQualified = query.contains(',')
        val candidates = ArrayList<PhotonOfflineRanking.Candidate>()
        // Query contract is executed against generated v2 SQLite by the Python
        // regression test; only joined aliases c/r expose normalized names.
        val sql = """
            SELECT p.name,p.street,p.house,c.name,r.name,p.postcode,
                   p.kind,p.importance,p.lat,p.lon
              FROM places_fts JOIN places p ON p.id = places_fts.docid
              JOIN cities c ON c.id=p.city_id
              JOIN regions r ON r.id=p.region_id
             WHERE places_fts MATCH ?
             ORDER BY CASE WHEN ? = '1' AND lower(?) = lower(p.name||' '||c.name)
                           THEN 0 WHEN ? = '1' THEN 1 ELSE 0 END,
                      CASE WHEN lower(p.name)=lower(?)
                                 OR lower(p.name) LIKE lower(?||' %')
                           THEN 0 ELSE 1 END,
                      CASE WHEN ? = '1' THEN
                          (p.lat - ?) * (p.lat - ?) +
                          (p.lon - ?) * (p.lon - ?) * ? * ?
                      ELSE 0 END,
                      p.importance DESC
             LIMIT 4000
        """.trimIndent()
        val validGps = gps?.takeIf {
            it.first.isFinite() && it.second.isFinite() &&
                it.first in -90.0..90.0 && it.second in -180.0..180.0
        }
        val lat = validGps?.first ?: 0.0
        val lon = validGps?.second ?: 0.0
        val longitudeScale = cos(Math.toRadians(lat))
        SQLiteDatabase.openDatabase(target.absolutePath, null,
            SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(sql, arrayOf(match,
                if (cityQualified) "1" else "0", normalized,
                if (cityQualified) "1" else "0",
                normalized, normalized,
                if (validGps != null) "1" else "0",
                lat.toString(), lat.toString(), lon.toString(), lon.toString(),
                longitudeScale.toString(), longitudeScale.toString())).use { rows ->
                while (rows.moveToNext()) {
                    candidates.add(PhotonOfflineRanking.Candidate(
                        rows.getString(0), rows.getString(1), rows.getString(2),
                        rows.getString(3), rows.getString(4), rows.getString(5),
                        rows.getString(6), rows.getDouble(7), rows.getDouble(8),
                        rows.getDouble(9)))
                }
            }
        }
        return PhotonOfflineRanking.rank(candidates, normalized, gps)
            .map { place ->
                val address = listOf(place.street +
                    (if (place.house.isNotBlank()) " " + place.house else ""),
                    place.postcode, place.city)
                    .filter { it.isNotBlank() }.distinct().joinToString(", ")
                val area = listOf(place.city, place.region, "Italia")
                    .filter { it.isNotBlank() }.distinct().joinToString(", ")
                PlaceSearchResult(place.name, address, area, place.lat, place.lon)
            }
    }
}

/**
 * Known Italian forecourt brands, used only for generic fuel-station searches.
 * Searching an individual brand still uses the normal Photon query unchanged.
 * Match the name itself, never the street/city containing a brand word.
 */
internal object FuelStationSearch {
    private val brandWords = listOf(
        "eni", "agip", "ip", "q8", "tamoil", "esso", "shell",
        "totalenergies", "beyfin", "retitalia", "vega", "costantin",
        "repsol"
    )
    val ftsQuery: String = brandWords.joinToString(" OR ") { it }

    fun isGenericQuery(query: String): Boolean =
        PhotonOfflineRanking.normalize(query) in setOf(
            "distributore", "distributori", "distributore benzina",
            "distributori benzina", "benzinaio", "benzinai",
            "stazione di servizio", "stazioni di servizio",
            "distributore carburante", "distributori carburante")

    fun isStation(name: String, kind: String): Boolean {
        val words = PhotonOfflineRanking.words(name)
        if (words.isEmpty()) return false
        val normalizedKind = PhotonOfflineRanking.normalize(kind)
        // The dump's address_type can identify the amenity even when its
        // display name is not the brand itself.
        if (normalizedKind in setOf("fuel", "gas station", "petrol station")) return true
        // Brand names are typically the complete POI label or begin it.
        // Reject unrelated addresses like "Via Eni" and localities containing IP.
        if (words.first() !in brandWords) return false
        return words.size == 1 || words.drop(1).any {
            it in setOf("easy", "station", "service", "distributore",
                "benzina", "carburanti", "live", "self", "gas", "oil")
        }
    }
}

/** Pure JVM ranking, exercised by Android unit tests without database or network. */
internal object PhotonOfflineRanking {
    data class Candidate(
        val name: String, val street: String, val house: String, val city: String,
        val region: String, val postcode: String, val kind: String,
        val importance: Double, val lat: Double, val lon: Double
    )
    private val tokenPattern = Regex("""[\p{L}\p{N}]+""")
    private val roadPrefixes = setOf("via", "viale", "piazza", "corso", "strada", "vicolo")

    fun normalize(text: String): String {
        val folded = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFKD)
            .replace(Regex("""\p{M}+"""), "")
        return tokenPattern.findAll(folded).joinToString(" ") { it.value }
    }
    fun words(text: String): List<String> = normalize(text).split(' ').filter { it.isNotEmpty() }

    fun rank(candidates: List<Candidate>, query: String,
             gps: Pair<Double, Double>?, limit: Int = 8): List<Candidate> {
        val q = normalize(query)
        if (q.isBlank()) return emptyList()
        val queryWords = q.split(' ').toSet()
        val roadQuery = q.substringBefore(' ') in roadPrefixes
        val validGps = gps?.takeIf {
            it.first.isFinite() && it.second.isFinite() &&
                it.first in -90.0..90.0 && it.second in -180.0..180.0
        }
        // Explicit locations override proximity, including "Via Roma Torino"
        // without a comma. For generic searches, nearby strong matches win.
        val qualifiedCity = candidates.any { candidate ->
            val city = normalize(candidate.city)
            val name = normalize(candidate.name)
            val street = normalize(candidate.street)
            city.isNotBlank() && q.endsWith(" " + city) &&
                (q.startsWith(name + " ") ||
                    (street.isNotBlank() && q.startsWith(street + " ")))
        }
        fun matchQuality(candidate: Candidate): Int {
            val name = normalize(candidate.name)
            val street = normalize(candidate.street)
            val city = normalize(candidate.city)
            val nw = name.split(' ').toSet()
            val cw = city.split(' ').toSet()
            val full = nw + cw + normalize(candidate.region).split(' ') +
                normalize(candidate.house).split(' ')
            val base = when {
                q == name -> 0
                city.isNotBlank() && q.endsWith(" " + city) &&
                    (q.startsWith(name + " ") || q.startsWith(street + " ")) -> 1
                city.isNotBlank() && q.endsWith(" " + city) &&
                    queryWords.all { it in (nw + cw) } -> 2
                queryWords.all { it in nw } -> 3
                queryWords.all { it in full } -> 8
                else -> 15
            }
            return base + if (!roadQuery && candidate.kind in setOf("street", "house")) 2 else 0
        }
        fun genericMatchGroup(candidate: Candidate): Int {
            val nameWords = normalize(candidate.name).split(' ').toSet()
            val strong = queryWords.all { it in nameWords }
            return when {
                strong && roadQuery && candidate.kind in setOf("street", "house") -> 0
                strong -> 1
                else -> 2
            }
        }
        val ranked = candidates.sortedWith(compareBy<Candidate> { candidate ->
            if (qualifiedCity) matchQuality(candidate)
            else genericMatchGroup(candidate)
        }.thenBy { candidate ->
            if (validGps == null) 0.0
            else {
                val dLat = candidate.lat - validGps.first
                val dLon = candidate.lon - validGps.second
                val scale = cos(Math.toRadians(validGps.first))
                dLat * dLat + dLon * dLon * scale * scale
            }
        }.thenBy { matchQuality(it) }
            .thenBy { candidate ->
                when (candidate.kind) {
                    "city", "locality" -> 0
                    "other" -> 1
                    "street" -> 2
                    else -> 3
                }
            }.thenByDescending { it.importance }
            .thenBy { it.name.length })
        /*
         * Previous ordering sorted exact names before nearby addresses, so
         * a distant "Via Roma" could outrank a nearby "Via Roma 12".
         * Keep the location override only for explicitly qualified searches.
         */
        val result = ArrayList<Candidate>(limit)
        val seen = HashSet<String>()
        for (place in ranked) {
            val name = normalize(place.name)
            val city = normalize(place.city)
            val key = if (place.kind in setOf("street", "city", "locality")) {
                place.kind + "|" + name + "|" + city
            } else {
                place.kind + "|" + name + "|" + city + "|" +
                    (place.lat * 100000).toLong() + "|" + (place.lon * 100000).toLong()
            }
            if (seen.add(key)) result.add(place)
            if (result.size >= limit) break
        }
        return result
    }
}
