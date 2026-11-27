package com.motogps.android.viewmodel

import org.json.JSONObject
import kotlin.math.*

/** Static geographically indexed SpeedPack records; never tied to a route.
 * Coordinates are original source positions, not inferred camera directions. */
internal class SpeedPackLocalData(
    raw: List<SpeedPackRouteMatcher.Evidence>,
    evidence: List<SpeedPackRouteMatcher.Evidence>,
    val hints: Set<SpeedPackAlertSelection.Hint>
) {
    companion object {
        private const val CELL = .01
        private fun cell(lat: Double, lon: Double) =
            floor(lat/CELL).toInt() to floor(lon/CELL).toInt()
    }
    val rawCount = raw.size
    private val grid = raw.filter { it.originalPoint.lat.isFinite() &&
        it.originalPoint.lon.isFinite() && it.originalPoint.lat in -90.0..90.0 &&
        it.originalPoint.lon in -180.0..180.0 }
        .groupBy { cell(it.originalPoint.lat,it.originalPoint.lon) }
    private val byRecord = evidence.groupBy { it.source to it.id }

    fun nearby(center: SpeedPackRouteMatcher.Point, radius: Double): List<SpeedPackRouteMatcher.Evidence> {
        val sx = 111195.0*cos(Math.toRadians(center.lat))
        if (sx <= 1000.0 || !radius.isFinite() || radius <= 0.0) return emptyList()
        val lo = cell(center.lat-radius/111195.0,center.lon-radius/sx)
        val hi = cell(center.lat+radius/111195.0,center.lon+radius/sx)
        val result = mutableListOf<SpeedPackRouteMatcher.Evidence>()
        for (y in lo.first..hi.first) for (x in lo.second..hi.second) {
            for (e in grid[y to x].orEmpty()) {
                val q = e.originalPoint
                if (hypot((q.lat-center.lat)*111195.0,(q.lon-center.lon)*sx) <= radius)
                    result.add(e)
            }
        }
        return result
    }

    fun evidenceFor(raw: List<SpeedPackRouteMatcher.Match>): List<SpeedPackRouteMatcher.Evidence> =
        raw.map { it.evidence.source to it.evidence.id }.distinct()
            .flatMap { byRecord[it].orEmpty() }
}

/** Rolling navigation corridor. The global route is retained only for absolute
 * chainage and projecting BLE coordinates; no whole-route camera scan.
 * Verification cache is scoped to this route instance and captured graph/
 * preferences (a new instance is created at every accepted route change). */
internal class SpeedPackLocalRoute(val points: List<SpeedPackRouteMatcher.Point>) {
    companion object {
        const val LOOK_AHEAD = 2000.0
        const val REFRESH_METERS = 400.0
        const val IMMEDIATE_METERS = 850.0
        private const val BEHIND_METERS = 25.0
        private const val GEO_RADIUS = 2200.0
    }

    private val cumulative = DoubleArray(points.size)
    init {
        require(points.size >= 2)
        for (i in 1 until points.size) {
            val a = points[i-1]
            val b = points[i]
            cumulative[i] = cumulative[i-1]+hypot((b.lat-a.lat)*111195.0,
                (b.lon-a.lon)*111195.0*cos(Math.toRadians((a.lat+b.lat)/2)))
        }
    }

    private fun segmentAt(distance: Double): Int {
        var lo = 1
        var hi = cumulative.lastIndex
        while (lo < hi) {
            val mid = (lo+hi)/2
            if (cumulative[mid] < distance) lo = mid+1 else hi = mid
        }
        return lo-1
    }

    data class Plan(val anchor: Double, val firstSegment: Int, val offset: Double,
                    val window: List<SpeedPackRouteMatcher.Point>,
                    val candidates: List<SpeedPackRouteMatcher.Match>,
                    val hints: Set<SpeedPackAlertSelection.Hint>,
                    val localSpots: Int, val rawMatches: Int)
    data class Verified(val alerts: List<SpeedPackAlertSelection.Alert>,
                        val deviceAlerts: List<SpeedPackAlertSelection.Alert>,
                        val verification: SpeedPackWayVerifier.Result,
                        val candidates: Int, val nativeCalls: Int)

    fun prepare(data: SpeedPackLocalData, progress: Double, check: () -> Unit): Plan {
        require(progress.isFinite())
        val anchor = progress.coerceIn(0.0,cumulative.last())
        val segment = segmentAt(anchor)
        val a = points[segment]
        val b = points[segment+1]
        val length = cumulative[segment+1]-cumulative[segment]
        val f = if (length > 0) ((anchor-cumulative[segment])/length).coerceIn(0.0,1.0) else 0.0
        val center = SpeedPackRouteMatcher.Point(a.lat+(b.lat-a.lat)*f,a.lon+(b.lon-a.lon)*f)
        // Keep real Valhalla vertices, never interpolate the trace shape.
        // Align to 8-segment buckets for reuse of successful trace windows.
        val low = (anchor-BEHIND_METERS).coerceAtLeast(0.0)
        val high = (anchor+LOOK_AHEAD).coerceAtMost(cumulative.last())
        val first = segmentAt(low)/8*8
        val last = min(points.lastIndex,(segmentAt(high)/8+1)*8)
        val window = points.subList(first,last+1)
        val offset = cumulative[first]
        val spots = data.nearby(center,GEO_RADIUS)
        check()
        val raw = SpeedPackRouteMatcher.nearby(window,spots,check,
            radiusMeters=SpeedPackRouteMatcher.PREVIEW_WAY_RADIUS_METERS,retainPassages=true)
            .filter { it.chainage+offset in low..high }
        val candidates = SpeedPackRouteMatcher.previewCandidates(window,raw,data.evidenceFor(raw),check)
            .filter { it.chainage+offset in low..high }
        return Plan(anchor,first,offset,window,candidates,data.hints,spots.size,raw.size)
    }

    private val traces = object : LinkedHashMap<List<SpeedPackRouteMatcher.Point>,JSONObject>(32,.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<List<SpeedPackRouteMatcher.Point>,JSONObject>?): Boolean =
            size > 128
    }

    /** First pass handles imminent cameras; second pass extends the context.
     * Both use the same local evidence and reuse validated shape responses.
     * Do not cache failures or traces with missing/foreign admin information. */
    fun verify(plan: Plan, immediate: Boolean, check: () -> Unit,
               trace: (List<SpeedPackRouteMatcher.Point>) -> JSONObject?): Verified {
        val candidates = if (immediate) plan.candidates.filter {
            it.chainage+plan.offset <= plan.anchor+IMMEDIATE_METERS
        } else plan.candidates
        var nativeCalls = 0
        val result = SpeedPackWayVerifier.verify(plan.window,candidates,check,
            fallbackToSegments=true) { window ->
            check()
            val key = window.toList()
            traces[key] ?: run {
                nativeCalls++
                trace(window)?.also { response ->
                    if (SpeedPackWayVerifier.segmentWays(window,response) != null &&
                        SpeedPackWayVerifier.italianWindow(response)) traces[key] = response
                }
            }
        }
        fun absolute(alerts: List<SpeedPackAlertSelection.Alert>) = alerts.map { alert ->
            val m = alert.match
            alert.copy(match=m.copy(chainage=m.chainage+plan.offset,
                segmentIndex=m.segmentIndex+plan.firstSegment))
        }
        return Verified(
            absolute(SpeedPackAlertSelection.select(candidates,result.status,plan.hints)),
            absolute(SpeedPackAlertSelection.select(candidates,
                result.status.filterKeys { it in result.italian },plan.hints)),
            result,candidates.size,nativeCalls)
    }
}
