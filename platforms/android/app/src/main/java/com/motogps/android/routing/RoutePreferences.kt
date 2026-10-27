package com.motogps.android.routing

import org.json.JSONObject

enum class RouteStyle { FASTEST, SHORTEST, SECONDARY }

/** Preferences reused for preview, turn-by-turn navigation and reroutes. */
data class RoutePreferences(
    val style: RouteStyle = RouteStyle.FASTEST,
    val avoidHighways: Boolean = false,
    val avoidTolls: Boolean = false,
    val avoidFerries: Boolean = false
) {
    fun applyTo(request: JSONObject): JSONObject {
        val auto = JSONObject()
        when (style) {
            RouteStyle.FASTEST -> Unit
            RouteStyle.SHORTEST -> auto.put("shortest", true)
            RouteStyle.SECONDARY -> {
                auto.put("use_highways", 0.0)
                auto.put("use_tracks", 0.0)
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
        return request
    }
}
