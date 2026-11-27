package com.motogps.android.viewmodel

import java.util.Locale

/** Small, bounded preferences, not a safety assessment. Unknown/nonstandard
 * OSM values contribute zero. Only explicit impassable excludes an anchor. */
internal object ExplorerRoadQuality {
    fun adjustment(profile: ExplorerProfile, surface: String?, smoothness: String?,
                   width: String?, lanes: String?, tunnel: String?): Double? {
        fun tag(s: String?) = s?.trim()?.lowercase(Locale.ROOT)
        val smooth = when(tag(smoothness)) {
            "impassable" -> return null
            "excellent" -> .06
            "good" -> .03
            "intermediate" -> -.025
            "bad" -> -.16
            "very_bad" -> -.25
            "horrible","very_horrible" -> -.35
            else -> 0.0
        }
        val floor = when(tag(surface)) {
            "asphalt" -> .04
            "sett","paving_stones" -> -.07
            "concrete:plates","concrete:lanes" -> -.05
            else -> 0.0
        }
        val metres = tag(width)?.removeSuffix(" m")?.trim()?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it > 0 }
        val narrow = when {
            metres == null -> 0.0
            metres < 2.5 -> -.10
            metres < 3.5 && profile == ExplorerProfile.TOURING -> -.04
            else -> 0.0
        }
        val count = tag(lanes)?.toIntOrNull()?.takeIf { it in 1..12 }
        val lane = when {
            count == 1 && profile == ExplorerProfile.TOURING -> -.035
            count != null && count >= 2 && profile == ExplorerProfile.TOURING -> .025
            else -> 0.0
        }
        val covered = if (profile == ExplorerProfile.PANORAMA &&
            tag(tunnel) in setOf("yes","building_passage","culvert","avalanche_protector")) -.06 else 0.0
        return (smooth+floor+narrow+lane+covered).coerceIn(-.45,.12)
    }
}
