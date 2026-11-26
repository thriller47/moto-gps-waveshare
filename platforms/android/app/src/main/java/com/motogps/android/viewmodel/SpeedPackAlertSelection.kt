package com.motogps.android.viewmodel

import kotlin.math.*

/** Only locally verified way IDs enter the experimental alert plan.
 * Camera direction is intentionally NOT an exclusion criterion.
 * The plan never modifies the underlying source records.
 */
internal object SpeedPackAlertSelection {
    data class Hint(val osmId: String, val atlasId: String)
    data class Alert(val match: SpeedPackRouteMatcher.Match,
                     val sources: Set<String>) {
        val chainage get() = match.chainage
    }

    fun select(matches: List<SpeedPackRouteMatcher.Match>,
               status: Map<SpeedPackRouteMatcher.Match, SpeedPackWayStatus>,
               hints: Set<Hint>): List<Alert> {
        // Collapse nearby same-source projections of one passage, not distinct
        // visits on an out-and-back. Prefer the smallest geometric residual.
        val eligible = matches.filter {
            status[it] == SpeedPackWayStatus.COINCIDENT
        }.sortedBy { it.chainage }
        val byRecord = mutableMapOf<Triple<String,String,Long>,MutableList<SpeedPackRouteMatcher.Match>>()
        for (m in eligible) {
            val e = m.evidence
            val list = byRecord.getOrPut(Triple(e.source,e.id,e.way)) { mutableListOf() }
            val last = list.lastOrNull()
            if (last != null && abs(last.chainage-m.chainage) <= 16.0) {
                if (m.crossTrack < last.crossTrack) list[list.lastIndex] = m
            } else list.add(m)
        }
        val ordered = byRecord.values.flatten().sortedBy { it.chainage }
        val used = BooleanArray(ordered.size)
        val result = mutableListOf<Alert>()
        for (i in ordered.indices) {
            if (used[i]) continue
            val a = ordered[i]
            var partner = -1
            var best = Double.POSITIVE_INFINITY
            for (j in i+1 until ordered.size) {
                val b = ordered[j]
                val gap = b.chainage-a.chainage
                if (gap > 20.0) break
                if (used[j] || a.evidence.way != b.evidence.way) continue
                val x = a.evidence
                val y = b.evidence
                val hint = when {
                    x.source == "osm" && y.source == "mapatlas" -> Hint(x.id,y.id)
                    y.source == "osm" && x.source == "mapatlas" -> Hint(y.id,x.id)
                    else -> continue
                }
                if (hint !in hints) continue
                val p = x.originalPoint
                val q = y.originalPoint
                val distance = hypot((p.lat-q.lat)*111195.0,
                    (p.lon-q.lon)*111195.0*cos(Math.toRadians((p.lat+q.lat)/2)))
                if (!distance.isFinite() || distance > 20.0) continue
                if (gap < best) { best = gap; partner = j }
            }
            used[i] = true
            if (partner >= 0) {
                used[partner] = true
                // Earlier projection gives a conservative distance to the pair.
                result.add(Alert(a,setOf(a.evidence.source,ordered[partner].evidence.source)))
            } else result.add(Alert(a,setOf(a.evidence.source)))
        }
        return result.sortedBy { it.chainage }
    }

    fun next(alerts: List<Alert>, progress: Double?, horizon: Double = 550.0): Alert? {
        if (progress == null || !progress.isFinite() || progress < 0 || horizon <= 0) return null
        return alerts.firstOrNull { it.chainage-progress in 0.0..horizon }
    }
}
