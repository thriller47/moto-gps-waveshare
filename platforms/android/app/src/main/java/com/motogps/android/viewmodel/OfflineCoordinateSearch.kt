package com.motogps.android.viewmodel

import java.util.Locale

/** Optional destination entry that works with the network completely disabled. */
internal object OfflineCoordinateSearch {
    private val pairPattern = Regex(
        """^\s*([+-]?\d+(?:\.\d+)?)\s*[,;]\s*([+-]?\d+(?:\.\d+)?)\s*$"""
    )

    fun parse(input: String): PlaceSearchResult? {
        val match = pairPattern.matchEntire(input) ?: return null
        val lat = match.groupValues[1].toDoubleOrNull() ?: return null
        val lon = match.groupValues[2].toDoubleOrNull() ?: return null
        if (!lat.isFinite() || !lon.isFinite() ||
            lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return PlaceSearchResult(
            name = "Coordinate GPS",
            address = String.format(Locale.US, "%.6f, %.6f", lat, lon),
            displayArea = "Coordinate",
            latitude = lat,
            longitude = lon
        )
    }
}
