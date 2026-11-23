package com.motogps.android.viewmodel

import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Route geometry is an estimate of road curvature, never a safe cornering-speed recommendation. */
internal data class RoadDnaCurves(
    val gentle: Int,
    val medium: Int,
    val tight: Int,
    val hairpins: Int,
    val densityPerKm: Double,
    val index: String
) {
    val total: Int get() = gentle + medium + tight + hairpins
}

internal data class RoadDnaHeightPoint(
    val distanceMeters: Double,
    val altitudeMeters: Double?
)

internal data class RoadDnaElevation(
    val points: List<RoadDnaHeightPoint>,
    val minMeters: Int,
    val maxMeters: Int,
    val ascentMeters: Int,
    val descentMeters: Int
)

internal data class RoadDnaTerrain(
    val curves: RoadDnaCurves,
    val elevation: RoadDnaElevation?
)

/**
 * Pure Android-side analysis. Uses only Valhalla route geometry and measured DEM
 * samples. It does not change routing, navigation progress, MotoMap or BLE.
 *
 * The curve thresholds are heuristic; OSM junction angles and sparse geometry
 * can look like curves. No lean, banking, surface or safe-speed claims are made.
 */
internal object RoadDnaTerrainAnalysis {
    private const val EARTH_METERS = 6371008.8
    private const val CURVE_STEP_METERS = 40.0

    fun analyze(route: JSONObject): RoadDnaTerrain {
        val shape = RoadDnaAnalysis.geometry(route)
        val total = route.optDouble("total_distance_m", 0.0)
        return RoadDnaTerrain(
            curves = curveMetrics(shape, total),
            elevation = elevation(route)
        )
    }

    private fun distance(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val lat1 = Math.toRadians(a.first)
        val lat2 = Math.toRadians(b.first)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.second - a.second)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2.0 * EARTH_METERS * atan2(sqrt(h.coerceAtLeast(0.0)),
            sqrt((1.0 - h).coerceAtLeast(0.0)))
    }

    /** Bearings are in radians; signed angle is normalised to [-pi, pi]. */
    private fun bend(
        a: Pair<Double, Double>, b: Pair<Double, Double>,
        c: Pair<Double, Double>
    ): Double {
        val scale = cos(Math.toRadians(b.first)).coerceAtLeast(0.02)
        val ax = (b.second - a.second) * scale
        val ay = b.first - a.first
        val bx = (c.second - b.second) * scale
        val by = c.first - b.first
        return Math.toDegrees(atan2(ax * by - ay * bx, ax * bx + ay * by))
    }

    internal fun curveMetrics(
        shape: List<Pair<Double, Double>>, nominalDistance: Double = 0.0
    ): RoadDnaCurves {
        val empty = RoadDnaCurves(0, 0, 0, 0, 0.0, "Dati insufficienti")
        if (shape.size < 3) return empty
        val lengths = ArrayList<Double>(shape.size)
        val clean = ArrayList<Pair<Double, Double>>(shape.size)
        var travelled = 0.0
        for (p in shape) {
            if (!p.first.isFinite() || !p.second.isFinite()) continue
            if (clean.isNotEmpty()) {
                val d = distance(clean.last(), p)
                if (d < 0.5 || d > 2000.0) continue
                travelled += d
            }
            clean.add(p)
            lengths.add(travelled)
        }
        if (clean.size < 3 || travelled < 150.0) return empty

        val sampled = ArrayList<Pair<Double, Double>>()
        var offset = 0.0
        var segment = 1
        while (offset < travelled && sampled.size < 50000) {
            while (segment < lengths.lastIndex && lengths[segment] < offset) segment++
            val start = lengths[segment - 1]
            val end = lengths[segment]
            val fraction = ((offset - start) / (end - start)).coerceIn(0.0, 1.0)
            val a = clean[segment - 1]
            val b = clean[segment]
            sampled.add((a.first + (b.first - a.first) * fraction) to
                (a.second + (b.second - a.second) * fraction))
            offset += CURVE_STEP_METERS
        }
        if (sampled.size < 3) return empty

        var gentle = 0
        var medium = 0
        var tight = 0
        var hairpins = 0
        var sumAngle = 0.0
        var peak = 0.0
        var previousIndex = -5
        var firstIndex = -1
        var direction = 0

        fun finish() {
            if (sumAngle >= 26.0) {
                // Approximate local radius, not measured vehicle dynamics.
                val arcMeters = max(CURVE_STEP_METERS,
                    (previousIndex.coerceAtLeast(0) - firstIndex + 2) * CURVE_STEP_METERS)
                val radius = arcMeters / Math.toRadians(sumAngle)
                when {
                    sumAngle >= 125.0 && radius < 170.0 -> hairpins++
                    radius < 85.0 || peak >= 52.0 -> tight++
                    radius < 225.0 || peak >= 20.0 -> medium++
                    else -> gentle++
                }
            }
            sumAngle = 0.0
            peak = 0.0
            direction = 0
        }
        for (i in 1 until sampled.lastIndex) {
            val delta = bend(sampled[i - 1], sampled[i], sampled[i + 1])
            val magnitude = abs(delta)
            if (magnitude < 7.0 || magnitude > 175.0) continue
            val sign = if (delta > 0) 1 else -1
            if (sumAngle > 0.0 && (sign != direction || i - previousIndex > 3)) {
                finish()
                firstIndex = -1
            }
            if (firstIndex == -1) firstIndex = i
            sumAngle += magnitude
            peak = max(peak, magnitude)
            previousIndex = i
            direction = sign
        }
        if (sumAngle > 0.0) finish()
        val count = gentle + medium + tight + hairpins
        val km = max(travelled, nominalDistance) / 1000.0
        val density = if (km > 0.0) count / km else 0.0
        return RoadDnaCurves(gentle, medium, tight, hairpins, density, when {
            count == 0 -> "Bassa (indicativa)"
            density < 0.9 -> "Bassa (indicativa)"
            density < 2.6 -> "Media (indicativa)"
            else -> "Alta (indicativa)"
        })
    }

    /**
     * Samples have their own chainage, independent of polyline vertices.
     * Missing DEM values are represented by JSON null and never interpolated.
     */
    internal fun elevation(route: JSONObject): RoadDnaElevation? {
        val source = route.optJSONArray("elevation_profile") ?: return null
        if (source.length() < 3) return null
        val points = ArrayList<RoadDnaHeightPoint>(source.length())
        var lastDistance = -1.0
        var valid = 0
        for (i in 0 until source.length()) {
            val item = source.optJSONObject(i) ?: continue
            val d = item.optDouble("distance_m", Double.NaN)
            if (!d.isFinite() || d <= lastDistance) continue
            val altitude = item.optDouble("elevation_m", Double.NaN)
                .takeIf { it.isFinite() && it in -500.0..9000.0 }
            points.add(RoadDnaHeightPoint(d, altitude))
            if (altitude != null) valid++
            lastDistance = d
        }
        if (points.size < 3 || valid < 3 ||
            valid.toDouble() / points.size < 0.70) return null

        val smooth = ArrayList<Double?>(points.size)
        for (i in points.indices) {
            if (points[i].altitudeMeters == null) {
                smooth.add(null)
                continue
            }
            val near = (max(0, i - 1)..min(points.lastIndex, i + 1))
                .mapNotNull { points[it].altitudeMeters }
                .sorted()
            smooth.add(near[near.size / 2])
        }
        var climbed = 0.0
        var descended = 0.0
        var last: Double? = null
        var smallAccumulation = 0.0
        for (h in smooth) {
            if (h == null) {
                last = null
                smallAccumulation = 0.0
                continue
            }
            val before = last
            if (before != null) {
                smallAccumulation += h - before
                if (abs(smallAccumulation) >= 3.0) {
                    if (smallAccumulation > 0.0) climbed += smallAccumulation
                    else descended -= smallAccumulation
                    smallAccumulation = 0.0
                }
            }
            last = h
        }
        val heights = points.mapNotNull { it.altitudeMeters }
        return RoadDnaElevation(points, heights.minOrNull()!!.roundToInt(),
            heights.maxOrNull()!!.roundToInt(), climbed.roundToInt(),
            descended.roundToInt())
    }
}
