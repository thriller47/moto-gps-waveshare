package com.motogps.android.viewmodel

import com.motogps.android.jni.GeoPointE6
import com.motogps.android.jni.MotoBLERouteGeometry
import com.motogps.android.jni.MotoBLEWaypointMarker
import kotlin.math.cos
import kotlin.math.roundToInt

/** Pending intermediate stops plus the optional final arrival in the BLE route window. */
internal object WaypointMarkerWindow {
    private const val MAX_MARKERS = 8
    private const val MAX_SNAP_METERS = 100.0

    // Index zero is the final arrival, reserved across Android, BLE and LVGL.
    // Up to eight numbered intermediate stops plus one blue arrival marker.
    fun visible(window: MotoBLERouteGeometry,
                pendingStops: List<PlaceSearchResult>,
                destination: PlaceSearchResult? = null): List<MotoBLEWaypointMarker> {
        if (window.points.size < 2) return emptyList()
        val lat0 = window.viewOrigin.latitudeE6 / 1e6
        val lon0 = window.viewOrigin.longitudeE6 / 1e6
        val xScale = 111_195.0 * cos(Math.toRadians(lat0))
        val yScale = 111_195.0
        fun x(p: GeoPointE6) = (p.longitudeE6 / 1e6 - lon0) * xScale
        fun y(p: GeoPointE6) = (p.latitudeE6 / 1e6 - lat0) * yScale
        val result = mutableListOf<MotoBLEWaypointMarker>()
        (pendingStops.take(MAX_MARKERS).mapIndexed { index, stop ->
            (index + 1) to stop
        } + listOfNotNull(destination?.let { 0 to it })).forEach { (index, stop) ->
            if (!stop.latitude.isFinite() || !stop.longitude.isFinite() ||
                kotlin.math.abs(stop.latitude) > 85.0 ||
                kotlin.math.abs(stop.longitude) > 180.0) return@forEach
            val wx = (stop.longitude - lon0) * xScale
            val wy = (stop.latitude - lat0) * yScale
            var best = MAX_SNAP_METERS * MAX_SNAP_METERS
            var snapped: GeoPointE6? = null
            for (i in 0 until window.points.lastIndex) {
                val a = window.points[i]
                val b = window.points[i + 1]
                val ax = x(a)
                val ay = y(a)
                val dx = x(b) - ax
                val dy = y(b) - ay
                val lengthSquared = dx * dx + dy * dy
                val t = if (lengthSquared > 0.01)
                    ((wx - ax) * dx + (wy - ay) * dy) / lengthSquared
                else 0.0
                val tClamped = t.coerceIn(0.0, 1.0)
                val px = ax + tClamped * dx
                val py = ay + tClamped * dy
                val error = (wx - px) * (wx - px) + (wy - py) * (wy - py)
                if (error < best) {
                    best = error
                    snapped = GeoPointE6(
                        (a.latitudeE6 + (b.latitudeE6 - a.latitudeE6) * tClamped).roundToInt(),
                        (a.longitudeE6 + (b.longitudeE6 - a.longitudeE6) * tClamped).roundToInt()
                    )
                }
            }
            snapped?.let { result.add(MotoBLEWaypointMarker(index, it)) }
        }
        return result
    }
}
