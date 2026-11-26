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
    UNVERIFIED("Way non verificata")
}

/** A record/pass, not necessarily a distinct physical device. */
data class SpeedPackPreviewMarker(
    val source: String, val recordId: String, val way: Long,
    val latitude: Double, val longitude: Double,
    val roadLatitude: Double, val roadLongitude: Double,
    val chainageMeters: Double, val crossTrackMeters: Double,
    val status: SpeedPackWayStatus, val limitKph: Int?
)

data class SpeedPackPreviewReport(
    val routeId: String, val markers: List<SpeedPackPreviewMarker>,
    val traceRequests: Int, val offline: Boolean
) {
    val coincident get() = markers.count { it.status == SpeedPackWayStatus.COINCIDENT }
    val different get() = markers.count { it.status == SpeedPackWayStatus.DIFFERENT }
    val unverified get() = markers.count { it.status == SpeedPackWayStatus.UNVERIFIED }
}

data class SpeedPackPreviewState(
    val loading: Boolean = false,
    val report: SpeedPackPreviewReport? = null,
    val error: String? = null
)

/** Shared engine for preview and active-route diagnostics. No network calls. */
internal object SpeedPackRouteAnalysis {
    data class Result(val matches: List<SpeedPackRouteMatcher.Match>,
                      val verification: SpeedPackWayVerifier.Result) {
        fun preview(routeId: String, offline: Boolean) = SpeedPackPreviewReport(
            routeId, matches.map { m ->
                val e = m.evidence
                SpeedPackPreviewMarker(e.source, e.id, e.way, e.originalPoint.lat,
                    e.originalPoint.lon, e.point.lat, e.point.lon, m.chainage,
                    m.crossTrack, verification.status[m] ?: SpeedPackWayStatus.UNVERIFIED,
                    e.limitKph)
            }, verification.requests, offline)
    }

    suspend fun analyze(context: Context, route: List<SpeedPackRouteMatcher.Point>,
                        provider: String?, preferences: RoutePreferences): Result {
        require(SpeedPackIndex.file(context).isFile) { "SpeedPack non installato" }
        require(route.size >= 2) { "Geometria Valhalla assente" }
        val job = currentCoroutineContext()[Job]
        val check: () -> Unit = { job?.ensureActive() }
        val evidence = SpeedPackIndex.roadEvidence(context, check)
        val candidates = SpeedPackRouteMatcher.match(route, evidence, check)
        val verification = if (provider == "valhalla-local") {
            SpeedPackWayVerifier.verify(route,candidates,check) { points ->
                OfflineValhallaRouter.traceWayAttributes(context,
                    points.map { it.lat to it.lon }, preferences)
            }
        } else SpeedPackWayVerifier.Result(0,0,candidates.size,0)
        return Result(candidates,verification)
    }
}
