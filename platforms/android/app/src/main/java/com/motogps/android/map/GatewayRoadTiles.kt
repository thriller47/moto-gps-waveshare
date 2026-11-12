package com.motogps.android.map

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * Pure Web Mercator z15 tile indexing shared by offline MotoMap and online
 * OpenFreeMap. No network clients or public OSM endpoints are used here.
 */
internal object GatewayRoadTiles {
    const val ZOOM = 15
    private const val GRID = 1 shl ZOOM

    data class Tile(val x: Int, val y: Int) {
        val key: String get() = "${ZOOM}_${x}_${y}"
    }

    fun tileFor(latitude: Double, longitude: Double): Tile {
        require(latitude.isFinite() && longitude.isFinite())
        val lat = latitude.coerceIn(-85.0, 85.0)
        val lon = longitude.coerceIn(-180.0, 180.0)
        val latRad = Math.toRadians(lat)
        val x = floor((lon + 180.0) / 360.0 * GRID).toInt().coerceIn(0, GRID - 1)
        val y = floor((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) * GRID / 2.0)
            .toInt().coerceIn(0, GRID - 1)
        return Tile(x, y)
    }

    fun around(latitude: Double, longitude: Double, radius: Int): List<Tile> {
        val center = tileFor(latitude, longitude)
        val result = mutableListOf<Tile>()
        for (dy in -radius..radius) for (dx in -radius..radius) {
            val x = center.x + dx
            val y = center.y + dy
            if (x in 0 until GRID && y in 0 until GRID) result.add(Tile(x, y))
        }
        return result.sortedWith(
            compareBy<Tile> { kotlin.math.abs(it.x - center.x) + kotlin.math.abs(it.y - center.y) }
                .thenBy { it.y }.thenBy { it.x }
        )
    }

}
