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

    private data class Choice(val stops: List<PlaceSearchResult>, val ids: List<Long>,
                              val score: Double, val lowerBound: Double)

    /** Two independently ranked road sectors, linked by Valhalla, not by an
     * assumed physical connection. Baseline plans and mandatory stops stay intact.
     * Budget: at most four additional routes, five possible sector pairs. */
    fun targeted(context: Context, origin: Pair<Double,Double>, km: Int,
                 profile: ExplorerProfile, base: List<List<PlaceSearchResult>>,
                 checkCancelled: () -> Unit = {}, diagnostic: (String) -> Unit = {}): List<List<PlaceSearchResult>> {
        val f = file(context)
        if (!f.isFile) return emptyList()
        val results = mutableListOf<List<PlaceSearchResult>>()
        val used = HashSet<Long>()
        SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val v2 = db.rawQuery("SELECT value FROM metadata WHERE key='schema'", null).use {
                it.moveToFirst() && it.getString(0) == "road-dna-roads-v2"
            }
            val extra = if (v2) "smoothness,width,lanes,tunnel" else "NULL,NULL,NULL,NULL"
            val radius = (km * 1000.0 * .08).coerceIn(5000.0,18000.0)
            fun hints(middle: Pair<Double,Double>): List<Hint> {
                checkCancelled()
                val dLat = radius/111195.0
                val dLon = dLat/cos(Math.toRadians(middle.first)).coerceAtLeast(.1)
                val nearby = if (profile == ExplorerProfile.PANORAMA) try {
                    RoadDnaAnalysis.nearby(context, middle.first, middle.second,
                        ceil(radius/1000+3).toInt().coerceIn(1,20), "consigliati")
                        .filter { it.category in setOf("belvedere","cascata","castello","lago","riserva") }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                  catch (e: Exception) { emptyList() } else emptyList()
                val hints = ArrayList<Hint>()
                val args = listOf(floor((middle.first-dLat)*20).toInt(),floor((middle.first+dLat)*20).toInt(),
                    floor((middle.second-dLon)*20).toInt(),floor((middle.second+dLon)*20).toInt()).map { it.toString() }.toTypedArray()
                var excluded = 0
                db.rawQuery("""SELECT id,name,lat,lon,a_lat,a_lon,b_lat,b_lon,curve_score,relief_m,length_m,highway,surface,$extra
                    FROM roads WHERE cell_lat BETWEEN ? AND ? AND cell_lon BETWEEN ? AND ?
                    ORDER BY curve_score DESC, length_m DESC, id LIMIT 600""",args).use { c ->
                    while (c.moveToNext()) {
                        checkCancelled()
                        val id = c.getLong(0)
                        if (id in used) continue
                        val center = c.getDouble(2) to c.getDouble(3)
                        val a = c.getDouble(4) to c.getDouble(5)
                        val b = c.getDouble(6) to c.getDouble(7)
                        if (listOf(center,a,b).any { !it.first.isFinite() || !it.second.isFinite() ||
                                it.first !in -85.0..85.0 || it.second !in -180.0..180.0 }) continue
                        val distance = meters(center,middle)
                        if (distance > radius || meters(a,b) < 150) continue
                        val adjustment = ExplorerRoadQuality.adjustment(profile,c.getString(12),
                            c.getString(13),c.getString(14),c.getString(15),c.getString(16))
                        if (adjustment == null) { excluded++; continue }
                        val curve = c.getDouble(8).coerceIn(0.0,1.0)
                        val relief = if (c.isNull(9)) null else (c.getDouble(9)/200).coerceIn(0.0,1.0)
                        val continuous = (c.getDouble(10)/2500).coerceIn(0.0,1.0)
                        val classBonus = when(c.getString(11)) {
                            "secondary","tertiary" -> .06
                            "residential" -> -.10
                            else -> 0.0
                        }
                        val quality = when(profile) {
                            ExplorerProfile.CURVES -> .64*curve + .36*continuous
                            ExplorerProfile.PANORAMA -> if(relief == null) .5*curve+.5*continuous
                                else .30*curve+.25*continuous+.45*relief
                            ExplorerProfile.TOURING -> .45*curve+.55*continuous
                        }
                        // Scenic POI proximity is a soft road-selection hint only.
                        // Never route to an unverified tourist centroid/entrance.
                        val scenic = nearby.maxOfOrNull {
                            .12 * (1-meters(center,it.latitude to it.longitude)/4000).coerceIn(0.0,1.0)
                        } ?: 0.0
                        hints.add(Hint(id,c.getString(1),a,b,center,
                            quality+classBonus+adjustment+scenic-.35*distance/radius))
                    }
                }
                diagnostic("schema=${if(v2) "v2" else "v1"} sector=${hints.size} excluded=$excluded")
                // Preserve spatial diversity within the shortlist, not just curve density.
                val best = ArrayList<Hint>()
                for (hint in hints.sortedWith(compareByDescending<Hint> { it.score }.thenBy { it.id })) {
                    if (best.none { meters(it.center,hint.center) < 750 }) best.add(hint)
                    if (best.size >= 12) break
                }
                return best
            }
            fun anchors(h: Hint, reverse: Boolean): List<PlaceSearchResult> {
                val label = h.name.ifBlank { "Strada indicizzata" }
                val coords = if(reverse) listOf(h.b,h.a) else listOf(h.a,h.b)
                return coords.mapIndexed { i, point ->
                    PlaceSearchResult(label + " · ${i+1}", "", "DNA stradale", point.first,point.second)
                }
            }
            fun choice(stops: List<PlaceSearchResult>, hs: List<Hint>): Choice? {
                val points = listOf(origin) + stops.map { it.latitude to it.longitude } + origin
                val length = points.zipWithNext().sumOf { (x,y) -> meters(x,y) }
                if (length > km*1100.0 || points.zipWithNext().any { (x,y) -> meters(x,y) < 80 }) return null
                val fit = abs(length/(km*1000.0) - .70)
                return Choice(stops,hs.map { it.id },hs.map { it.score }.average()-.25*fit,length)
            }
            for ((attempt,plan) in listOfNotNull(base.getOrNull(0),base.getOrNull(2),
                    base.getOrNull(3),base.getOrNull(5),base.getOrNull(1)).withIndex()) {
                checkCancelled()
                if (plan.size != 3) continue
                val first = if (attempt%2==0) 0 else 1
                val second = first+1
                val h1 = hints(plan[first].latitude to plan[first].longitude)
                val h2 = hints(plan[second].latitude to plan[second].longitude)
                var best: Choice? = null
                fun consider(c: Choice?) {
                    if (c != null && (best == null || c.score > best!!.score)) best = c
                }
                for (a in h1) for (b in h2) {
                    if (a.id == b.id || meters(a.center,b.center)<1000) continue
                    for (ar in listOf(false,true)) for (br in listOf(false,true)) {
                        val stops = plan.take(first)+anchors(a,ar)+anchors(b,br)+plan.drop(second+1)
                        consider(choice(stops,listOf(a,b)))
                    }
                }
                if (best == null) {
                    val middle = if(first==0) h2 else h1
                    for (h in middle) for (reverse in listOf(false,true))
                        consider(choice(listOf(plan[0])+anchors(h,reverse)+plan[2],listOf(h)))
                }
                best?.let {
                    results.add(it.stops)
                    used.addAll(it.ids)
                    diagnostic("candidate=${results.size} sections=${it.ids.joinToString(",")} score=${"%.3f".format(java.util.Locale.ROOT,it.score)} lower_bound_m=${it.lowerBound.roundToInt()}")
                }
                if(results.size >= 4) break
            }
        }
        return results
    }

    /** Require the anchors in route order, within 100 m of the actual polyline.
     * Still a proximity check, not exact OSM way matching. */
    fun anchorsReached(stops: List<PlaceSearchResult>, shape: List<Pair<Double,Double>>): Boolean {
        var from = 0
        for (stop in stops.filter { it.displayArea == "DNA stradale" }) {
            val point = stop.latitude to stop.longitude
            val match = (from until shape.lastIndex).firstOrNull { i ->
                RoadDnaAnalysis.distanceMeters(point,shape[i],shape[i+1]) <= 100.0
            } ?: return false
            from = match
        }
        return true
    }
}
