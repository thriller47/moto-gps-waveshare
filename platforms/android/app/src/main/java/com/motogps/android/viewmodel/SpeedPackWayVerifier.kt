package com.motogps.android.viewmodel

import com.motogps.android.routing.ValhallaRouteAdapter
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlin.math.*

/** Exact shape equality + segment-local way identity; diagnostic, not activation. */
internal object SpeedPackWayVerifier {
    data class Result(val confirmed: Int, val differentWay: Int, val unverified: Int,
                      val requests: Int,
                      val status: Map<SpeedPackRouteMatcher.Match, SpeedPackWayStatus> = emptyMap())

    /** Fail closed if returned shape differs from the original route vertices,
     * or an edge span is missing/contradictory. Do not compare only a set of IDs.
     */
    fun segmentWays(input: List<SpeedPackRouteMatcher.Point>, response: JSONObject): LongArray? {
        if (input.size !in 2..9) return null
        val encoded = response.optString("shape","")
        if (encoded.isEmpty()) return null
        val actual = try { ValhallaRouteAdapter.decodePolyline6(encoded) }
            catch (_: IllegalArgumentException) { return null }
        if (actual.size != input.size) return null
        if (actual.indices.any { i ->
                abs(actual[i].first-input[i].lat) > .000001 ||
                abs(actual[i].second-input[i].lon) > .000001 }) return null
        val edges = response.optJSONArray("edges") ?: return null
        val ways = LongArray(input.size-1)
        for (i in 0 until edges.length()) {
            val edge = edges.optJSONObject(i) ?: return null
            val begin = edge.optInt("begin_shape_index",-1)
            val end = edge.optInt("end_shape_index",-1)
            val way = edge.optLong("way_id",0)
            if (way <= 0 || begin < 0 || end <= begin || end >= input.size) return null
            for (s in begin until end) {
                if (ways[s] != 0L && ways[s] != way) return null
                ways[s] = way
            }
        }
        return ways.takeIf { array -> array.all { it > 0 } }
    }

    /** At most 16 windows of at most 8 segments / 400 m. Never walk the full
     * route in one native call. Missing/failed windows remain unverified. */
    fun verify(route: List<SpeedPackRouteMatcher.Point>,
               matches: List<SpeedPackRouteMatcher.Match>,
               checkCancelled: () -> Unit,
               trace: (List<SpeedPackRouteMatcher.Point>) -> JSONObject?): Result {
        var confirmed = 0
        var different = 0
        var requests = 0
        val status = mutableMapOf<SpeedPackRouteMatcher.Match, SpeedPackWayStatus>()
        for ((start, candidates) in matches.groupBy { it.segmentIndex / 8 * 8 }.toSortedMap()) {
            checkCancelled()
            if (requests >= 16) break
            if (start !in 0 until route.lastIndex) continue
            val end = min(start+8,route.lastIndex)
            val window = route.subList(start,end+1)
            val length = window.zipWithNext().sumOf { (a,b) ->
                hypot((b.lat-a.lat)*111195.0,(b.lon-a.lon)*111195.0*
                    cos(Math.toRadians((a.lat+b.lat)/2)))
            }
            if (!length.isFinite() || length !in 1.0..400.0) continue
            requests++
            val ways = try { trace(window)?.let { segmentWays(window,it) } }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
            checkCancelled()
            if (ways == null) continue
            for (candidate in candidates) {
                val way = ways.getOrNull(candidate.segmentIndex-start) ?: continue
                if (way == candidate.evidence.way) {
                    confirmed++
                    status[candidate] = SpeedPackWayStatus.COINCIDENT
                } else {
                    different++
                    status[candidate] = SpeedPackWayStatus.DIFFERENT
                }
            }
        }
        return Result(confirmed,different,matches.size-confirmed-different,requests,status)
    }
}
