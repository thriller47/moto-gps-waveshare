package com.motogps.android.viewmodel

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException

/** Local experimental SpeedPack; installing does not enable any alerts. */
internal object SpeedPackIndex {
    fun file(context: Context) = File(context.filesDir, "speedpack/speedpack-italy.sqlite")

    fun install(context: Context, uri: Uri, cancelled: () -> Boolean) {
        val target = file(context)
        target.parentFile?.mkdirs()
        val pending = File(target.parentFile, target.name + ".part")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                pending.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var copied = 0L
                    while (true) {
                        if (cancelled()) throw CancellationException()
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        require(copied <= 1024L * 1024 * 1024) { "SpeedPack troppo grande (limite 1 GiB)" }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error("Impossibile leggere SpeedPack")
            SQLiteDatabase.openDatabase(pending.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("PRAGMA quick_check", null).use { c ->
                    require(c.moveToFirst() && c.getString(0) == "ok" && !c.moveToNext()) {
                        "SpeedPack SQLite danneggiato"
                    }
                }
                db.rawQuery("SELECT value FROM metadata WHERE key='schema'", null).use { c ->
                    require(c.moveToFirst() && c.getString(0) == "speedpack-experimental-v1") {
                        "Formato SpeedPack non valido"
                    }
                }
                val queries = listOf(
                    "SELECT osm_node,lat,lon,maxspeed,direction,confidence,reason FROM cameras LIMIT 1",
                    "SELECT ref,lat,lon,class,limit_kph,facing_deg,approach,raw_json FROM mapatlas_records LIMIT 1",
                    "SELECT source,record_id,road_state,candidate_way,direction_state,direction_raw FROM road_assessment LIMIT 1",
                    "SELECT source,record_id,osm_way,segment_a,segment_b,road_lat,road_lon,bearing,offset_m,highway,name,road_ref,oneway,grade,shared_node FROM road_evidence LIMIT 1",
                    "SELECT osm_node,atlas_ref,distance_m,reciprocal_nearest,limit_state FROM cross_source_hints LIMIT 1",
                    "SELECT osm_relation,raw_tags,members FROM average_speed_candidates LIMIT 1",
                    "SELECT source,metadata_json FROM speedpack_sources LIMIT 1"
                )
                queries.forEachIndexed { index, sql ->
                    db.rawQuery(sql, null).use { c ->
                        val hasRow = c.moveToFirst()
                        if (index in 0..3 || index == 6) require(hasRow) { "SpeedPack incompleto" }
                    }
                }
                db.rawQuery("SELECT count(*) FROM speedpack_sources WHERE source IN ('osm','mapatlas')", null).use { c ->
                    require(c.moveToFirst() && c.getInt(0) == 2) { "Fonti SpeedPack mancanti" }
                }
            }
            if (cancelled()) throw CancellationException()
            Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
        } finally {
            pending.delete()
        }
    }
}
