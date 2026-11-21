package com.motogps.android.viewmodel

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import java.io.File
import java.io.IOException

/** Road DNA v1: separately versioned offline tourist SQLite component. */
internal object OfflineTouristPlaceSearch {
    private const val DATABASE = "tourist-italy-v1.sqlite"
    private const val MAX_BYTES = 512L * 1024 * 1024
    private const val SCHEMA = "road-dna-tourist-1"

    fun file(context: Context): File = File(context.filesDir, "road-dna/$DATABASE")

    internal fun checkDatabase(db: SQLiteDatabase) {
        if (db.version != 1) throw IOException("Road DNA: schema non compatibile")
        val tables = db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' " +
                "AND name IN ('tourist_poi','tourist_metadata')", null
        ).use { cursor -> cursor.count }
        if (tables != 2) throw IOException("Road DNA: tabelle mancanti")
        val marker = db.rawQuery(
            "SELECT value FROM tourist_metadata WHERE key='schema'", null
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        if (marker != SCHEMA) throw IOException("Road DNA: identificativo schema errato")
        val recordedCount = db.rawQuery(
            "SELECT value FROM tourist_metadata WHERE key='count'", null
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0).toLongOrNull() else null
        }
        val realCount = db.rawQuery("SELECT COUNT(*) FROM tourist_poi", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
        if (realCount <= 0L || recordedCount != realCount)
            throw IOException("Road DNA: POI mancanti o conteggio non coerente")
        val indexFound = db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='index' " +
                "AND name='tourist_category_lat_lon'", null
        ).use { it.moveToFirst() }
        if (!indexFound) throw IOException("Road DNA: indice spaziale mancante")
        val integrity = db.rawQuery("PRAGMA integrity_check", null).use {
            if (it.moveToFirst()) it.getString(0) else ""
        }
        if (integrity != "ok") throw IOException("Road DNA: SQLite danneggiato")
    }

    fun installed(context: Context): Boolean {
        val target = file(context)
        if (!target.isFile || target.length() < 1024) return false
        return try {
            SQLiteDatabase.openDatabase(target.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                .use { checkDatabase(it) }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Atomic within this component; caller verifies MotoPack SHA first. */
    fun install(context: Context, uri: Uri) {
        val target = file(context)
        val folder = target.parentFile ?: throw IOException("Road DNA: cartella non valida")
        if (!folder.exists() && !folder.mkdirs())
            throw IOException("Road DNA: impossibile creare la cartella")
        val pending = File(folder, "$DATABASE.part")
        val backup = File(folder, "$DATABASE.backup")
        if (pending.exists() && !pending.delete())
            throw IOException("Road DNA: file temporaneo occupato")
        try {
            val source = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Road DNA: impossibile leggere l'indice")
            var total = 0L
            source.use { input ->
                pending.outputStream().buffered().use { output ->
                    val bytes = ByteArray(128 * 1024)
                    while (true) {
                        val n = input.read(bytes)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) throw IOException("Road DNA: indice troppo grande")
                        output.write(bytes, 0, n)
                    }
                }
            }
            if (total < 1024) throw IOException("Road DNA: indice vuoto")
            SQLiteDatabase.openDatabase(
                pending.absolutePath, null, SQLiteDatabase.OPEN_READONLY
            ).use { checkDatabase(it) }
            if (backup.exists() && !backup.delete())
                throw IOException("Road DNA: vecchio backup occupato")
            if (target.exists() && !target.renameTo(backup))
                throw IOException("Road DNA: impossibile proteggere il precedente")
            if (!pending.renameTo(target)) {
                if (backup.exists()) backup.renameTo(target)
                throw IOException("Road DNA: impossibile installare l'indice")
            }
            if (backup.exists()) backup.delete()
        } finally {
            if (pending.exists()) pending.delete()
        }
    }

    data class TouristPlace(
        val osmType: String,
        val osmId: Long,
        val category: String,
        val name: String,
        val latitude: Double,
        val longitude: Double,
        val isArea: Boolean
    )

    /**
     * Candidate discovery near a position, not a routable destination.
     * The app must not navigate to a park/lake representative point directly.
     */
    fun searchNear(context: Context, lat: Double, lon: Double,
                   category: String, radiusKm: Double = 10.0,
                   limit: Int = 80): List<TouristPlace> {
        if (!lat.isFinite() || !lon.isFinite() ||
            lat !in -90.0..90.0 || lon !in -180.0..180.0 ||
            radiusKm !in 0.1..100.0 || limit !in 1..200) return emptyList()
        val target = file(context)
        if (!target.exists()) return emptyList()
        val dLat = radiusKm / 111.2
        val lonScale = kotlin.math.cos(Math.toRadians(lat)).coerceAtLeast(0.05)
        val dLon = radiusKm / (111.2 * lonScale)
        val args = arrayOf(category,
            (lat - dLat).toString(), (lat + dLat).toString(),
            (lon - dLon).toString(), (lon + dLon).toString(),
            limit.toString())
        SQLiteDatabase.openDatabase(
            target.absolutePath, null, SQLiteDatabase.OPEN_READONLY
        ).use { db ->
            val output = ArrayList<TouristPlace>()
            db.rawQuery(
                """SELECT osm_type,osm_id,category,name,lat,lon,geometry_type
                     FROM tourist_poi
                    WHERE category=?
                      AND max_lat >= ? AND min_lat <= ?
                      AND max_lon >= ? AND min_lon <= ?
                    LIMIT ?""", args
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    output.add(TouristPlace(
                        cursor.getString(0), cursor.getLong(1), cursor.getString(2),
                        cursor.getString(3), cursor.getDouble(4), cursor.getDouble(5),
                        cursor.getString(6) == "area"
                    ))
                }
            }
            return output
        }
    }
}
