package com.motogps.android.viewmodel

/** Preserve OSM entity semantics. A point or address is only a planning hint,
 * never proof of access; the Valhalla endpoint guard remains authoritative. */
internal object PhotonDestinationRanking {
    fun kind(key: String, value: String, type: String, street: String, house: String): String = when {
        key == "boundary" && value == "administrative" -> "Area amministrativa"
        type in setOf("country","state","county") -> "Area amministrativa"
        key == "place" && value in setOf("city","town","village","hamlet","suburb","quarter","neighbourhood","isolated_dwelling") ->
            "Centro abitato"
        street.isNotBlank() && house.isNotBlank() -> "Indirizzo"
        type == "house" -> "Indirizzo"
        type == "street" || key == "highway" -> "Strada"
        type in setOf("city","district","locality","town","village") -> "Località"
        else -> ""
    }

    private fun preference(place: PlaceSearchResult): Int = when {
        place.photonKind == "Centro abitato" -> 3
        place.photonStreetAddress -> 2
        place.photonKind == "Area amministrativa" -> 0
        else -> 1
    }

    /** Reorder only namesakes in the same declared country/region. Do not
     * globally boost addresses over unrelated city or street matches. Keep
     * administrative records available and keep existing order on ties. */
    fun preferNavigableNamesakes(input: List<PlaceSearchResult>): List<PlaceSearchResult> {
        val output = input.toMutableList()
        val groups = input.indices.filter { input[it].photonScope.substringBefore('|').isNotBlank() }
            .groupBy { PhotonOfflineRanking.normalize(input[it].name) to input[it].photonScope }
        for (indices in groups.values) {
            if (indices.size < 2) continue
            val ordered = indices.map { input[it] }.sortedByDescending { preference(it) }
            indices.forEachIndexed { index, slot -> output[slot] = ordered[index] }
        }
        return output
    }
}
