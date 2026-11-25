package com.motogps.android.viewmodel

import com.motogps.android.routing.CircularTourPlanner
import kotlin.math.*

enum class ExplorerProfile(val label: String) {
    CURVES("Curve"), PANORAMA("Panorami"), TOURING("Touring")
}

/** Relative preference score, not a road-quality or safety rating. */
data class ExplorerMetrics(
    val score: Int, val curves: Int, val hairpins: Int,
    val ascentMeters: Int?, val touristPlaces: Int?, val repeatedPercent: Int
)

internal object RoadDnaExplorer {
    const val MAX_ATTEMPTS = 8
    const val FINALISTS = 4

    fun stopPlans(lat: Double, lon: Double, km: Int, orientation: Int,
                  mandatory: List<PlaceSearchResult>): List<List<PlaceSearchResult>> {
        require(km in 30..300 && orientation in 0..359)
        val coordinates = mandatory.map { it.latitude to it.longitude }
        return listOf(0 to 0.8, 0 to 1.0, -35 to 0.8, 35 to 0.8,
            -65 to 0.7, 65 to 0.7, -25 to 1.0, 25 to 1.0).take(MAX_ATTEMPTS)
            .map { (offset, scale) ->
                CircularTourPlanner.planStops(lat, lon, (km * scale).roundToInt().coerceIn(30, 300),
                    (orientation + offset + 360) % 360, coordinates)
                    .mapIndexed { i, point -> mandatory.getOrNull(i) ?: PlaceSearchResult(
                        "Tappa automatica ${i + 1}", "", "Tour automatico", point.first, point.second) }
            }.distinctBy { stops -> stops.map { it.latitude to it.longitude } }
    }

    // Approximate repetition using 120m cells sampled on real route geometry.
    // Nearby parallel roads may overlap; this is not exact OSM edge identity.
    data class Footprint(val cells: Set<Pair<Int, Int>>, val repeated: Double)
    fun footprint(shape: List<Pair<Double, Double>>, origin: Pair<Double, Double>): Footprint {
        val scale = 111195.0 * cos(Math.toRadians(origin.first)).coerceAtLeast(0.1)
        val visited = HashSet<Pair<Int, Int>>()
        var previous: Pair<Int, Int>? = null
        var count = 0
        var repeated = 0
        for ((a, b) in shape.zipWithNext()) {
            val dx = (b.second - a.second) * scale
            val dy = (b.first - a.first) * 111195.0
            val n = ceil(hypot(dx, dy) / 60.0).toInt().coerceIn(1, 5000)
            for (i in 0..n) {
                val f = i.toDouble() / n
                val cell = floor(((a.second - origin.second) * scale + dx * f) / 120).toInt() to
                    floor(((a.first - origin.first) * 111195.0 + dy * f) / 120).toInt()
                if (cell == previous) continue
                previous = cell
                count++
                if (!visited.add(cell)) repeated++
            }
        }
        return Footprint(visited, if (count > 0) repeated.toDouble() / count else 1.0)
    }

    fun similarity(a: Footprint, b: Footprint): Double {
        val intersection = a.cells.count { it in b.cells }
        val union = a.cells.size + b.cells.size - intersection
        return if (union > 0) intersection.toDouble() / union else 1.0
    }

    fun metrics(option: RouteOption, targetKm: Int, profile: ExplorerProfile,
                footprint: Footprint, tourism: Int? = null): ExplorerMetrics {
        val terrain = RoadDnaTerrainAnalysis.analyze(option.rawRouteJson)
        val km = (option.distanceMeters / 1000.0).coerceAtLeast(1.0)
        val distanceFit = (1.0 - abs(km - targetKm) / targetKm * 2.0).coerceIn(0.0, 1.0)
        val maneuvers = option.rawRouteJson.optJSONArray("maneuvers")
        var artificial = 0
        for (i in 0 until (maneuvers?.length() ?: 0)) {
            when (maneuvers?.optJSONObject(i)?.optString("type")) {
                "roundabout" -> artificial++
                "u_turn" -> artificial += 3
            }
        }
        val bends = (terrain.curves.total - artificial).coerceAtLeast(0) / km
        val curveFit = if (profile == ExplorerProfile.TOURING)
            (1.0 - abs(bends - 1.5) / 3.0).coerceIn(0.0, 1.0)
            else (bends / 3.0).coerceIn(0.0, 1.0)
        val ascent = terrain.elevation?.ascentMeters
        val elevationFit = ascent?.let { (it / km / 18.0).coerceIn(0.0, 1.0) }
        val touristFit = tourism?.let { (it / km / 0.15).coerceIn(0.0, 1.0) }
        val weights = when (profile) {
            ExplorerProfile.CURVES -> listOf(35, 35, 20, 5, 5)
            ExplorerProfile.PANORAMA -> listOf(30, 20, 15, 15, 20)
            ExplorerProfile.TOURING -> listOf(40, 20, 30, 5, 5)
        }
        val values = listOf(distanceFit, curveFit, 1.0 - footprint.repeated, elevationFit, touristFit)
        var weighted = 0.0
        var available = 0
        for (i in values.indices) {
            val value = values[i] ?: continue
            weighted += value * weights[i]
            available += weights[i]
        }
        return ExplorerMetrics((100 * weighted / available.coerceAtLeast(1)).roundToInt().coerceIn(0, 100),
            terrain.curves.total, terrain.curves.hairpins, ascent, tourism,
            (footprint.repeated * 100).roundToInt())
    }

    fun touristCount(summary: RoadDnaSummary?, profile: ExplorerProfile): Int? {
        if (summary?.indexAvailable != true) return null
        val categories = if (profile == ExplorerProfile.PANORAMA) setOf(
            "belvedere", "cascata", "castello", "lago", "riserva", "vetta")
            else setOf("belvedere", "cascata", "castello", "lago", "riserva", "museo")
        return summary.places.filter { it.category in categories }
            .distinctBy { it.category to it.name.lowercase(java.util.Locale.ROOT) }.size
    }
}
