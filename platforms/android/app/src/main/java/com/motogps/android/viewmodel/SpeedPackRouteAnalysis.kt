package com.motogps.android.viewmodel

import android.content.Context
import com.motogps.android.routing.OfflineValhallaRouter
import com.motogps.android.routing.RoutePreferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class SpeedPackWayStatus(val label: String) {
    COINCIDENT("Way coincidente"),
    DIFFERENT("Way diversa"),
    UNVERIFIED("Way non verificata"),
    NEARBY_ONLY("Solo vicinanza (esclusa)")
}

/** A record/pass, not necessarily a distinct physical device. */
data class SpeedPackPreviewMarker(
    val source: String, val recordId: String, val way: Long,
    val latitude: Double, val longitude: Double,
    val roadLatitude: Double, val roadLongitude: Double,
    val chainageMeters: Double, val crossTrackMeters: Double,
    val status: SpeedPackWayStatus, val limitKph: Int?,
    val originalDistanceMeters: Double? = null,
    val candidateDetails: String = ""
)

data class SpeedPackPreviewReport(
    val routeId: String, val markers: List<SpeedPackPreviewMarker>,
    val traceRequests: Int, val offline: Boolean,
    val rawScanned: Int = 0, val rawWithinCorridor: Int = 0
) {
    val coincident get() = markers.count { it.status == SpeedPackWayStatus.COINCIDENT }
    val different get() = markers.count { it.status == SpeedPackWayStatus.DIFFERENT }
    val unverified get() = markers.count { it.status == SpeedPackWayStatus.UNVERIFIED }
    val nearbyOnly get() = markers.count { it.status == SpeedPackWayStatus.NEARBY_ONLY }
}

data class SpeedPackPreviewState(
    val loading: Boolean = false,
    val report: SpeedPackPreviewReport? = null,
    val error: String? = null
)

/** Shared engine for preview and active-route diagnostics. No network calls. */
internal object SpeedPackRouteAnalysis {
    data class Result(val matches: List<SpeedPackRouteMatcher.Match>,
                      val verification: SpeedPackWayVerifier.Result,
                      val nearbyOnly: List<SpeedPackRouteMatcher.Match> = emptyList(),
                      val rawNearby: List<SpeedPackRouteMatcher.Match> = emptyList(),
                      val rawScanned: Int = 0) {
        fun preview(routeId: String, offline: Boolean): SpeedPackPreviewReport {
            val byRecord = matches.groupBy { it.evidence.source to it.evidence.id }
            val markers = rawNearby.map { raw ->
                val key = raw.evidence.source to raw.evidence.id
                val alternatives = byRecord[key].orEmpty()
                fun status(m: SpeedPackRouteMatcher.Match) =
                    verification.status[m] ?: SpeedPackWayStatus.UNVERIFIED
                val selected = alternatives.minWithOrNull(
                    compareBy<SpeedPackRouteMatcher.Match> {
                        when (status(it)) {
                            SpeedPackWayStatus.COINCIDENT -> 0
                            SpeedPackWayStatus.UNVERIFIED -> 1
                            else -> 2
                        }
                    }.thenBy { it.crossTrack }.thenBy { it.evidence.way })
                val e = selected?.evidence ?: raw.evidence
                val details = alternatives.sortedBy { it.evidence.way }.joinToString("\n") { m ->
                    val w = m.evidence
                    "way ${w.way} · ${status(m).label} · " +
                        "${w.highway.orEmpty()} · proiezione ${kotlin.math.round(m.crossTrack).toInt()} m" +
                        (if (w.grade) " · grade review" else "") +
                        (w.roadState?.let { " · " + it } ?: "") +
                        (w.directionState?.let { " · " + it } ?: "")
                }
                SpeedPackPreviewMarker(e.source,e.id,e.way,raw.evidence.originalPoint.lat,
                    raw.evidence.originalPoint.lon,e.point.lat,e.point.lon,raw.chainage,
                    selected?.crossTrack ?: raw.crossTrack,
                    selected?.let { status(it) } ?: SpeedPackWayStatus.NEARBY_ONLY,
                    raw.evidence.limitKph,raw.crossTrack,details)
            }
            return SpeedPackPreviewReport(routeId,markers,verification.requests,
                offline,rawScanned,rawNearby.size)
        }
    }

    suspend fun analyze(context: Context, route: List<SpeedPackRouteMatcher.Point>,
                        provider: String?, preferences: RoutePreferences,
                        includeRawPreview: Boolean = false): Result {
        require(SpeedPackIndex.file(context).isFile) { "SpeedPack non installato" }
        require(route.size >= 2) { "Geometria Valhalla assente" }
        val job = currentCoroutineContext()[Job]
        val check: () -> Unit = { job?.ensureActive() }
        // Preview coverage is independent of way selection. The wider 500 m
        // radius only displays records; compare ways within 50 m of the route.
        val rawSpots = if (includeRawPreview) SpeedPackIndex.rawSpots(context,check) else emptyList()
        val rawNearby = if (includeRawPreview)
            SpeedPackRouteMatcher.nearby(route,rawSpots,check) else emptyList()
        val candidates = if (includeRawPreview) {
            val keys = rawNearby.filter { it.crossTrack <= SpeedPackRouteMatcher.PREVIEW_WAY_RADIUS_METERS }
                .map { it.evidence.source to it.evidence.id }.toSet()
            val evidence = SpeedPackIndex.allRoadEvidence(context,keys,check)
            SpeedPackRouteMatcher.previewCandidates(route,rawNearby,evidence,check)
        } else {
            val evidence = SpeedPackIndex.roadEvidence(context,check)
            SpeedPackRouteMatcher.match(route,evidence,check)
        }
        val verification = if (provider == "valhalla-local") {
            SpeedPackWayVerifier.verify(route,candidates,check) { points ->
                OfflineValhallaRouter.traceWayAttributes(context,
                    points.map { it.lat to it.lon }, preferences)
            }
        } else SpeedPackWayVerifier.Result(0,0,candidates.size,0)
        val strictRecords = candidates.map { it.evidence.source to it.evidence.id }.toHashSet()
        val nearbyOnly = rawNearby.filter { (it.evidence.source to it.evidence.id) !in strictRecords }
        return Result(candidates,verification,nearbyOnly,rawNearby,rawSpots.size)
    }
}
