package com.motogps.android.viewmodel

import com.motogps.android.routing.ValhallaRouteAdapter
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlin.math.*

/** Exact shape equality + segment-local way identity; diagnostic, not activation. */
internal object SpeedPackWayVerifier {
    data class Result(val confirmed: Int, val differentWay: Int, val unverified: Int,
                      val requests: Int,
                      val status: Map<SpeedPackRouteMatcher.Match, SpeedPackWayStatus> = emptyMap(),
                      val italian: Set<SpeedPackRouteMatcher.Match> = emptySet())

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

    /** Missing or non-Italian admin data never authorizes a device alert. */
    fun italianWindow(response: JSONObject): Boolean {
        val admins = response.optJSONArray("admins") ?: return false
        if (admins.length() == 0) return false
        return (0 until admins.length()).all {
            admins.optJSONObject(it)?.optString("country_code","") == "IT"
        }
    }

    /** At most 16 windows of at most 8 segments / 400 m. Never walk the full
     * route in one native call. Missing/failed windows remain unverified. */
    fun verify(route: List<SpeedPackRouteMatcher.Point>,
               matches: List<SpeedPackRouteMatcher.Match>,
               checkCancelled: () -> Unit,
               fallbackToSegments: Boolean = false,
               trace: (List<SpeedPackRouteMatcher.Point>) -> JSONObject?): Result {
        var confirmed = 0
        var different = 0
        var requests = 0
        val status = mutableMapOf<SpeedPackRouteMatcher.Match, SpeedPackWayStatus>()
        val italian = mutableSetOf<SpeedPackRouteMatcher.Match>()
        // Prefer shared bounded windows. If a bucket spans more than 400 m,
        // try its individual candidate segments instead of dropping the bucket.
        fun windowLength(start: Int, end: Int): Double =
            (start until end).sumOf { i ->
                val a = route[i]
                val b = route[i+1]
                hypot((b.lat-a.lat)*111195.0,(b.lon-a.lon)*111195.0*
                    cos(Math.toRadians((a.lat+b.lat)/2)))
            }
        val windows = matches.groupBy { it.segmentIndex / 8 * 8 }.toSortedMap().flatMap { (start, candidates) ->
            if (start !in 0 until route.lastIndex) emptyList()
            else {
                val end = min(start+8,route.lastIndex)
                if (windowLength(start,end) in 1.0..400.0) listOf(Triple(start,end,candidates))
                else candidates.groupBy { it.segmentIndex }.toSortedMap()
                    .filter { (i,_) -> i in 0 until route.lastIndex }
                    .map { (i,group) -> Triple(i,i+1,group) }
            }
        }
        fun evaluate(start: Int, end: Int, candidates: List<SpeedPackRouteMatcher.Match>): Boolean {
            checkCancelled()
            if (requests >= 16 || windowLength(start,end) !in 1.0..400.0) return false
            val window = route.subList(start,end+1)
            requests++
            val response = try { trace(window) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
            val ways = response?.let { segmentWays(window,it) }
            val isItalian = response?.let { italianWindow(it) } == true
            checkCancelled()
            if (ways == null) return false
            for (candidate in candidates) {
                val way = ways.getOrNull(candidate.segmentIndex-start) ?: continue
                if (way == candidate.evidence.way) {
                    confirmed++
                    status[candidate] = SpeedPackWayStatus.COINCIDENT
                    if (isItalian) italian.add(candidate)
                } else {
                    different++
                    status[candidate] = SpeedPackWayStatus.DIFFERENT
                }
            }
            return true
        }
        for ((start,end,candidates) in windows) {
            checkCancelled()
            if (requests >= 16) break
            val success = evaluate(start,end,candidates)
            // Navigation only: a group containing a junction or a clipped
            // shape may fail even when the candidate segment is verifiable.
            if (!success && fallbackToSegments && end-start > 1) {
                for ((i,group) in candidates.groupBy { it.segmentIndex }.toSortedMap()) {
                    if (requests >= 16) break
                    if (i in start until end) evaluate(i,i+1,group)
                }
            }
        }
        return Result(confirmed,different,matches.size-confirmed-different,requests,status,italian)
    }
}
