package com.motogps.android.viewmodel

/** Do not mistake a prefix/FTS match in the Italian database for a confirmed
 * destination. Explicit qualifiers must also match returned place information. */
internal object HybridPlaceSearch {
    fun confident(query: String, place: PlaceSearchResult): Boolean {
        val q = PhotonOfflineRanking.normalize(query)
        if (q.isBlank()) return false
        val name = PhotonOfflineRanking.normalize(place.name)
        val address = PhotonOfflineRanking.normalize(place.address)
        val area = PhotonOfflineRanking.normalize(place.displayArea)
        if (!query.contains(',') && (q == name || q == address)) return true
        val words = q.split(' ').toSet()
        val known = (name+" "+address+" "+area).split(' ').toSet()
        if (!known.containsAll(words)) return false
        val main = PhotonOfflineRanking.normalize(query.substringBefore(','))
        if (query.contains(',')) {
            // Require the leading place/street name, not merely a coincidental
            // region or country token. Remaining words have to be present too.
            return main.isNotBlank() && (main == name || main == address ||
                address.startsWith("$main ") || name.startsWith("$main "))
        }
        return name.isNotBlank() && q.startsWith("$name ") ||
            address.isNotBlank() && (q.startsWith("$address ") || address.startsWith("$q "))
    }

    fun merge(query: String, local: List<PlaceSearchResult>,
              online: List<PlaceSearchResult>, limit: Int = 8): List<PlaceSearchResult> {
        val strongLocal = local.filter { confident(query,it) }
        val strongOnline = online.filter { confident(query,it) }
        val weakLocal = local.filterNot { confident(query,it) }
        val weakOnline = online.filterNot { confident(query,it) }
        // Keep exact matches first. If neither source can confirm a match,
        // interleave the partial results, remote first: the local eight
        // must never hide all online results (e.g. translated place names).
        val ranked = strongLocal + strongOnline +
            (0 until maxOf(weakLocal.size,weakOnline.size)).flatMap { i ->
                listOfNotNull(weakOnline.getOrNull(i),weakLocal.getOrNull(i))
            }
        val unique = ArrayList<PlaceSearchResult>()
        for (place in ranked) {
            val duplicate = unique.any {
                PhotonOfflineRanking.normalize(it.name) == PhotonOfflineRanking.normalize(place.name) &&
                    kotlin.math.abs(it.latitude-place.latitude)<0.0005 &&
                    kotlin.math.abs(it.longitude-place.longitude)<0.0005
            }
            if (!duplicate) unique.add(place)
            if (unique.size>=limit) break
        }
        return unique
    }
}
