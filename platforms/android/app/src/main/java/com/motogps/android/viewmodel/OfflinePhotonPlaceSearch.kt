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
    private const val VERSION = 1
    fun file(context: Context): File = File(context.filesDir, "photon/" + DATABASE)
    fun installed(context: Context): Boolean = file(context).let { it.isFile && it.length() > 0 }

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
            val size = context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE),
                null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
            } ?: -1L
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
                db.rawQuery("SELECT docid FROM places_fts LIMIT 1", null).use { rows ->
                    if (!rows.moveToFirst()) throw IOException("Indice FTS4 Photon vuoto")
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

    /** An installed index always takes priority over the remote Photon service. */
    fun search(context: Context, query: String, gps: Pair<Double, Double>?): List<PlaceSearchResult> {
        val target = file(context)
        if (!target.isFile) throw IOException("Indice Photon non installato")
        val match = tokenize(query)
        if (match.isBlank()) return emptyList()
        val normalized = PhotonOfflineRanking.normalize(query)
        val candidates = ArrayList<PhotonOfflineRanking.Candidate>()
        val sql = """
            SELECT p.name,p.street,p.house,p.city,p.region,p.postcode,
                   p.kind,p.importance,p.lat,p.lon
              FROM places_fts JOIN places p ON p.id = places_fts.docid
             WHERE places_fts MATCH ?
             ORDER BY CASE WHEN lower(p.name)=lower(?) THEN 0
                           WHEN lower(?)=lower(p.name||' '||p.city) THEN 1
                           WHEN lower(?) LIKE lower(p.name||' %') THEN 2
                           ELSE 3 END,
                      p.importance DESC
             LIMIT 4000
        """.trimIndent()
        SQLiteDatabase.openDatabase(target.absolutePath, null,
            SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(sql, arrayOf(match, query.trim(), query.trim(), query.trim())).use { rows ->
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
        val ranked = candidates.sortedWith(compareBy<Candidate> { candidate ->
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
            base + if (!roadQuery && candidate.kind in setOf("street", "house")) 2 else 0
        }.thenBy { candidate ->
            when (candidate.kind) {
                "city", "locality" -> 0
                "other" -> 1
                "street" -> 2
                else -> 3
            }
        }.thenByDescending { it.importance }
            .thenBy { candidate ->
                if (validGps == null) 0.0
                else {
                    val dLat = candidate.lat - validGps.first
                    val dLon = candidate.lon - validGps.second
                    val scale = cos(Math.toRadians(validGps.first))
                    dLat * dLat + dLon * dLon * scale * scale
                }
            }.thenBy { it.name.length })
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
