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

    /** Only selected non-grade road candidates; not a verified road identity. */
    fun roadEvidence(context: Context, cancelled: () -> Unit): List<SpeedPackRouteMatcher.Evidence> {
        val target = file(context)
        if (!target.isFile) return emptyList()
        val result = mutableListOf<SpeedPackRouteMatcher.Evidence>()
        SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("""
                SELECT a.source,a.record_id,e.osm_way,e.road_lat,e.road_lon,e.bearing,e.oneway,
                    CASE WHEN a.source='osm' AND c.osm_way=e.osm_way
                         AND c.confidence='medium' THEN c.direction ELSE NULL END,
                    COALESCE(c.lat,m.lat),COALESCE(c.lon,m.lon),COALESCE(c.maxspeed,m.limit_kph)
                FROM road_assessment a
                JOIN road_evidence e ON e.source=a.source AND e.record_id=a.record_id
                    AND e.osm_way=a.candidate_way
                LEFT JOIN cameras c ON a.source='osm' AND CAST(c.osm_node AS TEXT)=a.record_id
                LEFT JOIN mapatlas_records m ON a.source='mapatlas' AND m.ref=a.record_id AND m.class='spot'
                WHERE (c.osm_node IS NOT NULL OR m.ref IS NOT NULL)
                    AND a.road_state IN ('single_way_candidate','shared_node_candidate','continuity_candidate')
                    AND e.grade=0 AND e.offset_m<=15 AND a.direction_state!='legacy_conflict'
                LIMIT 100001
            """.trimIndent(), null).use { c ->
                while (c.moveToNext()) {
                    cancelled()
                    require(result.size < 100000) { "Troppi candidati SpeedPack" }
                    result.add(SpeedPackRouteMatcher.Evidence(c.getString(0), c.getString(1),
                        c.getLong(2), SpeedPackRouteMatcher.Point(c.getDouble(3),c.getDouble(4)),
                        c.getDouble(5), if (c.isNull(6)) null else c.getString(6),
                        if (c.isNull(7)) null else c.getString(7),
                        SpeedPackRouteMatcher.Point(c.getDouble(8),c.getDouble(9)),
                        if (c.isNull(10)) null else c.getInt(10)))
                }
            }
        }
        return result
    }

    /** All ways for raw records found near the preview, including ambiguous
     * and grade/direction review cases. Read once; retain only requested IDs. */
    fun allRoadEvidence(context: Context, keys: Set<Pair<String,String>>,
                        cancelled: () -> Unit): List<SpeedPackRouteMatcher.Evidence> {
        if (keys.isEmpty()) return emptyList()
        val result = mutableListOf<SpeedPackRouteMatcher.Evidence>()
        SQLiteDatabase.openDatabase(file(context).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("""
                SELECT e.source,e.record_id,e.osm_way,e.road_lat,e.road_lon,e.bearing,e.oneway,
                    CASE WHEN e.source='osm' AND c.osm_way=e.osm_way
                         AND c.confidence='medium' THEN c.direction ELSE NULL END,
                    COALESCE(c.lat,m.lat),COALESCE(c.lon,m.lon),COALESCE(c.maxspeed,m.limit_kph),
                    e.offset_m,e.grade,e.highway,a.road_state,a.direction_state
                FROM road_evidence e
                JOIN road_assessment a ON a.source=e.source AND a.record_id=e.record_id
                LEFT JOIN cameras c ON e.source='osm' AND CAST(c.osm_node AS TEXT)=e.record_id
                LEFT JOIN mapatlas_records m ON e.source='mapatlas' AND m.ref=e.record_id AND m.class='spot'
                WHERE (c.osm_node IS NOT NULL OR m.ref IS NOT NULL)
                ORDER BY e.source,e.record_id,e.osm_way
            """.trimIndent(), null).use { c ->
                while (c.moveToNext()) {
                    cancelled()
                    val source = c.getString(0)
                    val id = c.getString(1)
                    if ((source to id) !in keys) continue
                    require(result.size < 100000) { "Troppi candidati SpeedPack" }
                    result.add(SpeedPackRouteMatcher.Evidence(source,id,c.getLong(2),
                        SpeedPackRouteMatcher.Point(c.getDouble(3),c.getDouble(4)),
                        c.getDouble(5), if (c.isNull(6)) null else c.getString(6),
                        if (c.isNull(7)) null else c.getString(7),
                        SpeedPackRouteMatcher.Point(c.getDouble(8),c.getDouble(9)),
                        if (c.isNull(10)) null else c.getInt(10),
                        c.getDouble(11),c.getInt(12)!=0,c.getString(13),c.getString(14),c.getString(15)))
                }
            }
        }
        return result
    }

    /** Existing reciprocal cross-source hints; not a new geographic merge. */
    fun duplicateHints(context: Context, cancelled: () -> Unit): Set<SpeedPackAlertSelection.Hint> {
        val result = mutableSetOf<SpeedPackAlertSelection.Hint>()
        SQLiteDatabase.openDatabase(file(context).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("""
                SELECT CAST(osm_node AS TEXT),atlas_ref
                FROM cross_source_hints
                WHERE reciprocal_nearest=1 AND distance_m>=0 AND distance_m<=20
                LIMIT 100001
            """.trimIndent(),null).use { c ->
                while (c.moveToNext()) {
                    cancelled()
                    require(result.size < 100000) { "Troppi indizi SpeedPack" }
                    result.add(SpeedPackAlertSelection.Hint(c.getString(0),c.getString(1)))
                }
            }
        }
        return result
    }

    /** Unfiltered spot coordinates. */
    fun rawSpots(context: Context, cancelled: () -> Unit): List<SpeedPackRouteMatcher.Evidence> {
        val target = file(context)
        if (!target.isFile) return emptyList()
        val result = mutableListOf<SpeedPackRouteMatcher.Evidence>()
        SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("""
                SELECT 'osm',CAST(osm_node AS TEXT),lat,lon,maxspeed FROM cameras
                UNION ALL
                SELECT 'mapatlas',ref,lat,lon,limit_kph FROM mapatlas_records WHERE class='spot'
                LIMIT 100001
            """.trimIndent(), null).use { c ->
                while (c.moveToNext()) {
                    cancelled()
                    require(result.size < 100000) { "Troppi record SpeedPack" }
                    val point = SpeedPackRouteMatcher.Point(c.getDouble(2),c.getDouble(3))
                    result.add(SpeedPackRouteMatcher.Evidence(c.getString(0),c.getString(1),0L,
                        point,0.0,null,limitKph = if (c.isNull(4)) null else c.getInt(4)))
                }
            }
        }
        return result
    }

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
