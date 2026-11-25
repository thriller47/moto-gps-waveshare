package com.motogps.android.routing

import org.json.JSONObject

enum class RouteStyle { FASTEST, SHORTEST, SECONDARY, TOURING, PANORAMIC }

/** Preferences reused for preview, turn-by-turn navigation and reroutes. */
data class RoutePreferences(
    val style: RouteStyle = RouteStyle.FASTEST,
    val avoidHighways: Boolean = false,
    val avoidTolls: Boolean = false,
    val avoidFerries: Boolean = false,
    val curveLevel: Int = 0
) {
    init {
        require(curveLevel in 0..3) { "Intensità curve non valida" }
    }

    val useCurvyAlternates: Boolean
        get() = (style == RouteStyle.TOURING && curveLevel > 0) || style == RouteStyle.PANORAMIC

    val motorcycle: Boolean
        get() = style == RouteStyle.TOURING || style == RouteStyle.PANORAMIC

    // Native costing searches the graph itself, including on multi-stop routes.
    // No geometry-based alternative selection in the production routers.
    fun requestAlternates(): Int = 0

    val nativeCurvature: Double
        get() = when (style) {
            RouteStyle.PANORAMIC -> 0.8
            RouteStyle.TOURING -> listOf(0.5, 0.6, 0.8, 1.0)[curveLevel]
            else -> 0.5
        }

    fun applyTo(request: JSONObject): JSONObject {
        val costing = if (motorcycle) "motorcycle" else "auto"
        request.put("costing", costing)
        val auto = JSONObject()
        when (style) {
            RouteStyle.FASTEST -> Unit
            RouteStyle.SHORTEST -> auto.put("shortest", true)
            RouteStyle.SECONDARY -> {
                auto.put("use_highways", 0.0)
                auto.put("use_tracks", 0.0)
            }
            RouteStyle.TOURING, RouteStyle.PANORAMIC -> {
                // Valhalla >=3.9.2 supports curvature at edge-cost level.
                auto.put("use_curvature", nativeCurvature)
                auto.put("use_trails", 0.0)
                auto.put("use_highways", if (style == RouteStyle.PANORAMIC) 0.05 else if (curveLevel == 3) 0.0 else 0.15)
                auto.put("exclude_unpaved", true)
                if (style == RouteStyle.PANORAMIC) {
                    auto.put("use_tolls", 0.0)
                }
            }
        }
        // Valhalla's 'shortest' ignores other costs/penalties.
        if (style != RouteStyle.SHORTEST) {
            if (avoidHighways) auto.put("use_highways", 0.0)
            if (avoidTolls) auto.put("use_tolls", 0.0)
            if (avoidFerries) auto.put("use_ferry", 0.0)
        }
        if (auto.length() > 0)
            request.put("costing_options", JSONObject().put(costing, auto))
        request.remove("alternates")
        return request
    }
}
