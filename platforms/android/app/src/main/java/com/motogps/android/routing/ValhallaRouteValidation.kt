package com.motogps.android.routing

import org.json.JSONObject
import kotlin.math.*

/** Validate the actual raw Valhalla leg geometry before the adapter copies
 * requested coordinates into the route envelope. A successful response is
 * not evidence of coverage: graph extracts can correlate to distant roads. */
internal object ValhallaRouteValidation {
    const val MAX_SNAP_M = 300.0

    private fun distance(a: Pair<Double,Double>, b: Pair<Double,Double>): Double {
        val dLat = Math.toRadians(b.first-a.first)
        val dLon = Math.toRadians(b.second-a.second)
        val h = sin(dLat/2).pow(2) + cos(Math.toRadians(a.first)) *
            cos(Math.toRadians(b.first)) * sin(dLon/2).pow(2)
        return 2*6371008.8*asin(sqrt(h.coerceIn(0.0,1.0)))
    }

    private fun hasFerry(summary: JSONObject?): Boolean =
        summary?.optBoolean("has_ferry",false) == true ||
            summary?.optBoolean("ferry",false) == true

    fun requireValid(response: JSONObject, requested: List<Pair<Double,Double>>,
                     avoidFerries: Boolean) {
        val trip = response.getJSONObject("trip")
        val legs = trip.getJSONArray("legs")
        require(requested.size >= 2 && legs.length() == requested.size-1) {
            "Percorso incompleto: numero di tratte diverso dalle tappe richieste"
        }
        require(!avoidFerries || !hasFerry(trip.optJSONObject("summary"))) {
            "Percorso rifiutato: contiene traghetti esclusi"
        }
        for (i in 0 until legs.length()) {
            val leg = legs.getJSONObject(i)
            val points = ValhallaRouteAdapter.decodePolyline6(leg.getString("shape"))
            require(points.size >= 2) { "Percorso incompleto: geometria tratta ${i+1}" }
            val start = distance(requested[i],points.first())
            val end = distance(requested[i+1],points.last())
            require(start.isFinite() && end.isFinite() &&
                start <= MAX_SNAP_M && end <= MAX_SNAP_M) {
                "Percorso fuori copertura: tratta ${i+1}, scarto partenza ${start.roundToInt()} m, arrivo ${end.roundToInt()} m"
            }
            if (avoidFerries) {
                require(!hasFerry(leg.optJSONObject("summary"))) {
                    "Percorso rifiutato: contiene traghetti esclusi"
                }
                val steps = leg.optJSONArray("maneuvers")
                // Standard Valhalla summaries or maneuver flags must be
                // present. Missing both is not proof of a ferry-free route.
                val declared = trip.optJSONObject("summary")?.has("has_ferry") == true ||
                    leg.optJSONObject("summary")?.has("has_ferry") == true
                require(declared || (steps != null && steps.length()>0)) {
                    "Percorso rifiutato: impossibile verificare i traghetti"
                }
                for (j in 0 until (steps?.length() ?: 0)) {
                    val m = steps?.optJSONObject(j) ?: continue
                    require(!m.optBoolean("ferry",false) &&
                        m.optInt("type",-1) !in 28..29 &&
                        !m.optString("travel_type").equals("ferry",ignoreCase=true)) {
                        "Percorso rifiutato: contiene traghetti esclusi"
                    }
                }
            }
        }
    }
}
