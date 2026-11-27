package com.motogps.android.viewmodel

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.*

/** Optional experimental index; no dependency on MotoPack or on public routing services. */
internal object RoadDnaRoadIndex {
    fun file(context: Context) = File(context.filesDir, "road-index/road-dna-italy.sqlite")

    fun install(context: Context, uri: Uri, cancelled: () -> Boolean, progress: (Long) -> Unit): Long {
        val target = file(context)
        target.parentFile?.mkdirs()
        val pending = File(target.parentFile, target.name + ".part")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                pending.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var copied = 0L
                    while (true) {
                        if (cancelled()) throw kotlinx.coroutines.CancellationException()
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        require(copied <= 1024L * 1024 * 1024) { "Indice troppo grande (limite 1 GiB)" }
                        output.write(buffer, 0, count)
                        progress(copied)
                    }
                }
            } ?: error("Impossibile leggere il file")
            SQLiteDatabase.openDatabase(pending.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("PRAGMA quick_check", null).use { c ->
                    require(c.moveToFirst() && c.getString(0) == "ok") { "Indice SQLite danneggiato" }
                }
                val schema = db.rawQuery("SELECT value FROM metadata WHERE key='schema'", null).use { c ->
                    require(c.moveToFirst()) { "Formato Road DNA non valido" }
                    c.getString(0)
                }
                require(schema in setOf("road-dna-roads-v1","road-dna-roads-v2")) { "Formato Road DNA non valido" }
                if (schema == "road-dna-roads-v2") {
                    db.rawQuery("SELECT smoothness,width,lanes,maxspeed,lit,incline,bridge,tunnel FROM roads LIMIT 1",null).use { c ->
                        require(c.moveToFirst()) { "Indice V2 vuoto" }
                    }
                }
                db.rawQuery("SELECT osm_way,part,name,highway,surface,lat,lon,a_lat,a_lon,b_lat,b_lon,length_m,bends,hairpins,relief_m,curve_score,cell_lat,cell_lon FROM roads LIMIT 1", null).use { c ->
                    require(c.moveToFirst()) { "Indice vuoto" }
                }
            }
            if (cancelled()) throw kotlinx.coroutines.CancellationException()
            Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return target.length()
        } finally {
            pending.delete()
        }
    }

    private data class Hint(val id: Long, val name: String, val a: Pair<Double,Double>,
                            val b: Pair<Double,Double>, val center: Pair<Double,Double>,
                            val score: Double)
    private fun meters(a: Pair<Double,Double>, b: Pair<Double,Double>): Double =
        hypot((a.first-b.first)*111195.0,
            (a.second-b.second)*111195.0*cos(Math.toRadians((a.first+b.first)/2)))

    /** Keep eight baseline plans, add at most four plans targeting actual road vertices.
     * Never edit a mandatory stop. No hints for plans containing mandatory locations.
     */
    fun targeted(context: Context, origin: Pair<Double,Double>, km: Int,
                 profile: ExplorerProfile, base: List<List<PlaceSearchResult>>): List<List<PlaceSearchResult>> {
        val f = file(context)
        if (!f.isFile) return emptyList()
        val results = mutableListOf<List<PlaceSearchResult>>()
        val used = HashSet<Long>()
        SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            for (plan in listOfNotNull(base.getOrNull(0),base.getOrNull(2),
                    base.getOrNull(3),base.getOrNull(5),base.getOrNull(1))) {
                if (plan.size != 3) continue
                val middle = plan[1].latitude to plan[1].longitude
                val radius = (km * 1000.0 * .08).coerceIn(5000.0,18000.0)
                val dLat = radius/111195.0
                val dLon = dLat/cos(Math.toRadians(middle.first)).coerceAtLeast(.1)
                val hints = ArrayList<Hint>()
                val args = listOf(floor((middle.first-dLat)*20).toInt(),floor((middle.first+dLat)*20).toInt(),
                    floor((middle.second-dLon)*20).toInt(),floor((middle.second+dLon)*20).toInt()).map { it.toString() }.toTypedArray()
                db.rawQuery("""SELECT id,name,lat,lon,a_lat,a_lon,b_lat,b_lon,curve_score,relief_m,length_m,highway,surface
                    FROM roads WHERE cell_lat BETWEEN ? AND ? AND cell_lon BETWEEN ? AND ?
                    ORDER BY curve_score DESC, length_m DESC, id LIMIT 240""",args).use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        if (id in used) continue
                        val center = c.getDouble(2) to c.getDouble(3)
                        val a = c.getDouble(4) to c.getDouble(5)
                        val b = c.getDouble(6) to c.getDouble(7)
                        if (listOf(center,a,b).any { !it.first.isFinite() || !it.second.isFinite() ||
                                it.first !in -85.0..85.0 || it.second !in -180.0..180.0 }) continue
                        val distance = meters(center,middle)
                        if (distance > radius || meters(a,b) < 150) continue
                        val curve = c.getDouble(8).coerceIn(0.0,1.0)
                        val relief = if (c.isNull(9)) null else (c.getDouble(9)/200).coerceIn(0.0,1.0)
                        val continuous = (c.getDouble(10)/2500).coerceIn(0.0,1.0)
                        val classBonus = if (c.getString(11) in listOf("secondary","tertiary")) .08 else 0.0
                        val surfaceBonus = if (c.getString(12) != "unknown") .04 else 0.0
                        val quality = when(profile) {
                            ExplorerProfile.CURVES -> .8*curve + .2*continuous
                            ExplorerProfile.PANORAMA -> if(relief == null) .65*curve+.35*continuous
                                else .45*curve+.25*continuous+.30*relief
                            ExplorerProfile.TOURING -> .45*curve+.55*continuous
                        }
                        hints.add(Hint(id,c.getString(1),a,b,center,
                            quality+classBonus+surfaceBonus-.35*distance/radius))
                    }
                }
                val previous = plan[0].latitude to plan[0].longitude
                val next = plan[2].latitude to plan[2].longitude
                for (hint in hints.sortedByDescending { it.score }) {
                    val (a,b) = if(meters(previous,hint.a)+meters(hint.b,next) <=
                        meters(previous,hint.b)+meters(hint.a,next)) hint.a to hint.b else hint.b to hint.a
                    val points = listOf(origin,previous,a,b,next,origin)
                    if(points.zipWithNext().sumOf { (x,y) -> meters(x,y) } > km*1100.0) continue
                    val label = hint.name.ifBlank { "Strada indicizzata" }
                    results.add(listOf(plan[0],
                        PlaceSearchResult(label + " · 1","", "DNA stradale",a.first,a.second),
                        PlaceSearchResult(label + " · 2","", "DNA stradale",b.first,b.second),plan[2]))
                    used.add(hint.id)
                    break
                }
                if(results.size >= 4) break
            }
        }
        return results
    }

    /** Verify proximity of both anchor coordinates to the actual returned route.
     * This is not exact OSM edge matching, nor a guarantee of road quality.
     */
    fun anchorsReached(stops: List<PlaceSearchResult>, shape: List<Pair<Double,Double>>): Boolean =
        stops.filter { it.displayArea == "DNA stradale" }.all { stop ->
            shape.zipWithNext().any { (a,b) ->
                RoadDnaAnalysis.distanceMeters(stop.latitude to stop.longitude,a,b) <= 100.0
            }
        }
}
