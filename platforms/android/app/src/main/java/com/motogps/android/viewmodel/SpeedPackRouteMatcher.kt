package com.motogps.android.viewmodel

import kotlin.math.*

/** Diagnostic candidates only: shape proximity cannot establish OSM way identity. */
internal object SpeedPackRouteMatcher {
    data class Point(val lat: Double, val lon: Double)
    data class Evidence(val source: String, val id: String, val way: Long,
                        val point: Point, val bearing: Double, val oneway: String?,
                        val relativeDirection: String? = null,
                        val originalPoint: Point = point,
                        val limitKph: Int? = null)
    data class Match(val evidence: Evidence, val chainage: Double, val crossTrack: Double,
                     val segmentIndex: Int)
    private const val CELL = .002
    private const val MAX_CROSS = 8.0
    private const val MAX_SEGMENT = 250.0

    private fun cell(p: Point) = floor(p.lat / CELL).toInt() to floor(p.lon / CELL).toInt()
    private fun valid(p: Point) = p.lat.isFinite() && p.lon.isFinite() &&
        p.lat in -90.0..90.0 && p.lon in -180.0..180.0
    private fun angle(a: Double, b: Double) = abs(((a-b+540.0)%360.0)-180.0)
    private fun allowed(e: Evidence, bearing: Double): Boolean {
        val delta = angle(bearing,e.bearing)
        val forward = delta <= 20.0
        val backward = delta >= 160.0
        if (!forward && !backward) return false
        if (e.oneway in setOf("yes","1","true") && !forward) return false
        if (e.oneway == "-1" && !backward) return false
        if (e.relativeDirection == "forward" && !forward) return false
        if (e.relativeDirection == "backward" && !backward) return false
        return true
    }

    /** Index the static projected road points, then walk the full route once.
     * Keep repeated passages through a point at different route chainages.
     * No assumption about facingDeg or unknown camera direction.
     */
    fun match(route: List<Point>, evidence: List<Evidence>,
              checkCancelled: () -> Unit = {}): List<Match> {
        if (route.size < 2 || route.any { !valid(it) }) return emptyList()
        val grid = evidence.filter { valid(it.point) && it.bearing.isFinite() &&
            it.bearing in 0.0..360.0 }.groupBy { cell(it.point) }
        val matches = mutableMapOf<Pair<String,String>, MutableList<Match>>()
        var chainage = 0.0
        for (i in 0 until route.lastIndex) {
            checkCancelled()
            val a = route[i]
            val b = route[i+1]
            val sx = 111195.0 * cos(Math.toRadians((a.lat+b.lat)/2))
            val dx = (b.lon-a.lon)*sx
            val dy = (b.lat-a.lat)*111195.0
            val length = hypot(dx,dy)
            if (length < .1) continue
            // Sparse long segments are not trustworthy local road evidence.
            if (length <= MAX_SEGMENT && sx > 1000) {
                val bearing = (Math.toDegrees(atan2(dx,dy))+360)%360
                val lo = cell(Point(min(a.lat,b.lat)-MAX_CROSS/111195.0,
                    min(a.lon,b.lon)-MAX_CROSS/sx))
                val hi = cell(Point(max(a.lat,b.lat)+MAX_CROSS/111195.0,
                    max(a.lon,b.lon)+MAX_CROSS/sx))
                for (y in lo.first..hi.first) for (x in lo.second..hi.second) {
                    for (e in grid[y to x].orEmpty()) {
                        if (!allowed(e,bearing)) continue
                        val ex = (e.point.lon-a.lon)*sx
                        val ey = (e.point.lat-a.lat)*111195.0
                        val t = ((ex*dx+ey*dy)/(length*length)).coerceIn(0.0,1.0)
                        val cross = hypot(ex-t*dx,ey-t*dy)
                        if (cross > MAX_CROSS) continue
                        val m = Match(e,chainage+t*length,cross,i)
                        val list = matches.getOrPut(e.source to e.id) { mutableListOf() }
                        val last = list.lastOrNull()
                        // Adjacent segments near a vertex are the same passage.
                        if (last != null && abs(last.chainage-m.chainage) <= 16) {
                            if (cross < last.crossTrack) list[list.lastIndex] = m
                        } else list.add(m)
                    }
                }
            }
            chainage += length
        }
        return matches.values.flatten().sortedBy { it.chainage }
    }

    /** Preview-only raw proximity, never a road match. */
    fun nearby(route: List<Point>, spots: List<Evidence>,
               checkCancelled: () -> Unit = {}): List<Match> {
        if (route.size < 2 || route.any { !valid(it) }) return emptyList()
        val radius = 50.0
        val grid = spots.filter { valid(it.originalPoint) }.groupBy { cell(it.originalPoint) }
        val matches = mutableMapOf<Pair<String,String>, MutableList<Match>>()
        var chainage = 0.0
        for (i in 0 until route.lastIndex) {
            checkCancelled()
            val a = route[i]
            val b = route[i+1]
            val sx = 111195.0 * cos(Math.toRadians((a.lat+b.lat)/2))
            val dx = (b.lon-a.lon)*sx
            val dy = (b.lat-a.lat)*111195.0
            val length = hypot(dx,dy)
            if (length < .1) continue
            if (length <= 50_000.0 && sx > 1000) {
                val parts = ceil(length/250.0).toInt().coerceAtLeast(1)
                val seen = HashSet<Pair<String,String>>()
                for (part in 0 until parts) {
                    checkCancelled()
                    val t0 = part.toDouble()/parts
                    val t1 = (part+1).toDouble()/parts
                    val lat0 = a.lat+(b.lat-a.lat)*t0
                    val lat1 = a.lat+(b.lat-a.lat)*t1
                    val lon0 = a.lon+(b.lon-a.lon)*t0
                    val lon1 = a.lon+(b.lon-a.lon)*t1
                    val lo = cell(Point(min(lat0,lat1)-radius/111195.0,min(lon0,lon1)-radius/sx))
                    val hi = cell(Point(max(lat0,lat1)+radius/111195.0,max(lon0,lon1)+radius/sx))
                    for (y in lo.first..hi.first) for (x in lo.second..hi.second) {
                        for (e in grid[y to x].orEmpty()) {
                            val key = e.source to e.id
                            if (!seen.add(key)) continue
                            val ex = (e.originalPoint.lon-a.lon)*sx
                            val ey = (e.originalPoint.lat-a.lat)*111195.0
                            val t = ((ex*dx+ey*dy)/(length*length)).coerceIn(0.0,1.0)
                            val cross = hypot(ex-t*dx,ey-t*dy)
                            if (cross > radius) continue
                            val m = Match(e,chainage+t*length,cross,i)
                            val list = matches.getOrPut(key) { mutableListOf() }
                            val last = list.lastOrNull()
                            if (last != null && abs(last.chainage-m.chainage) <= 100.0) {
                                if (cross < last.crossTrack) list[list.lastIndex] = m
                            } else list.add(m)
                        }
                    }
                }
            }
            chainage += length
        }
        return matches.values.flatten().sortedBy { it.chainage }
    }

    fun next(matches: List<Match>, progress: Double?, horizon: Double = 550.0): Match? {
        if (progress == null || !progress.isFinite() || progress < 0 || horizon <= 0) return null
        return matches.firstOrNull { it.chainage-progress in 0.0..horizon }
    }
}
