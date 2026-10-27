package com.motogps.android.routing

import org.json.JSONObject

enum class RouteStyle { FASTEST, SHORTEST, SECONDARY, TOURING }

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
        get() = style == RouteStyle.TOURING && curveLevel > 0

    /** Keep the normal requests light; only Touring with curve preference asks
     * for alternative paths, without extra HTTP requests or routing jobs. */
    fun requestAlternates(): Int = if (useCurvyAlternates) 2 else 0

    fun applyTo(request: JSONObject): JSONObject {
        val auto = JSONObject()
        when (style) {
            RouteStyle.FASTEST -> Unit
            RouteStyle.SHORTEST -> auto.put("shortest", true)
            RouteStyle.SECONDARY -> {
                auto.put("use_highways", 0.0)
                auto.put("use_tracks", 0.0)
            }
            RouteStyle.TOURING -> {
                // Touring stays on auto costing, already verified on the
                // imported Italian tiles. No dependence on motorcycle BETA.
                auto.put("use_highways", 0.15)
                auto.put("use_tracks", 0.0)
                auto.put("exclude_unpaved", true)
            }
        }
        // Valhalla's 'shortest' ignores other costs/penalties.
        if (style != RouteStyle.SHORTEST) {
            if (avoidHighways) auto.put("use_highways", 0.0)
            if (avoidTolls) auto.put("use_tolls", 0.0)
            if (avoidFerries) auto.put("use_ferry", 0.0)
        }
        if (auto.length() > 0)
            request.put("costing_options", JSONObject().put("auto", auto))
        if (requestAlternates() > 0) request.put("alternates", requestAlternates())
        return request
    }
}
