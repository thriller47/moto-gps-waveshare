package com.motogps.android.diagnostics

import android.content.Context
import android.os.SystemClock
import com.motogps.android.jni.GeoPointE6
import com.motogps.android.jni.MotoBLEMapScene
import com.motogps.android.jni.MotoBLENavigationSnapshot
import com.motogps.android.jni.MotoBLERouteGeometry
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Explicitly enabled, bounded location/geometry recording for Windows replay.
 *
 * Distinct from DiagnosticLog: the ordinary diagnostics ZIP NEVER includes
 * coordinates. The user must turn recording on and explicitly export this file.
 * All file I/O takes place on one private background worker, never on GPS/BLE.
 */
object ReplayTraceRecorder {
    private const val MAX_BYTES = 24L * 1024L * 1024L
    private const val MAX_DURATION_MS = 60L * 60L * 1000L
    private val worker = Executors.newSingleThreadExecutor { job ->
        Thread(job, "MotoGpsPrivateReplay").apply { isDaemon = true }
    }
    @Volatile private var active = false
    @Volatile private var startElapsed = 0L
    @Volatile private var traceFile: File? = null

    fun isRecording(): Boolean = active

    fun start(context: Context) {
        if (active) return
        val root = File(context.applicationContext.filesDir, "diagnostics")
        require(root.mkdirs() || root.isDirectory) { "Directory diagnostica non disponibile" }
        val path = File(root, "replay-current.jsonl")
        startElapsed = SystemClock.elapsedRealtime()
        traceFile = path
        active = true
        worker.execute {
            try {
                path.writeText(JSONObject().put("schema", "moto-replay-v1")
                    .put("type", "meta").put("t_ms", 0)
                    .put("source", "Android-BLE-outgoing")
                    .put("captured_at_ms", System.currentTimeMillis())
                    .toString() + "\n")
            } catch (_: Exception) { active = false }
        }
    }

    fun stop() { active = false }

    private fun point(p: GeoPointE6): JSONArray =
        JSONArray().put(p.latitudeE6 / 1e6).put(p.longitudeE6 / 1e6)

    private fun write(data: JSONObject) {
        if (!active) return
        val elapsed = SystemClock.elapsedRealtime() - startElapsed
        if (elapsed !in 0..MAX_DURATION_MS) {
            active = false
            return
        }
        data.put("t_ms", elapsed)
        val line = data.toString() + "\n"
        val file = traceFile ?: return
        worker.execute {
            try {
                if (file.length() + line.length * 3L > MAX_BYTES) {
                    active = false
                } else file.appendText(line)
            } catch (_: Exception) { active = false }
        }
    }

    /** Accepted raw GNSS fix; used later for true Android rerun, not the LVGL replay. */
    fun fix(lat: Double, lon: Double, speedMps: Float,
            headingDeg: Float?, accuracyM: Float) {
        if (!active || !lat.isFinite() || !lon.isFinite()) return
        val e = JSONObject().put("type", "fix").put("lat", lat).put("lon", lon)
            .put("speed_mps", speedMps.toDouble())
            .put("accuracy_m", accuracyM.toDouble())
        if (headingDeg != null && headingDeg.isFinite())
            e.put("heading_deg", headingDeg.toDouble())
        write(e)
    }

    /** The same moving 24-point geometry window submitted to BLE. */
    fun route(g: MotoBLERouteGeometry) {
        if (!active) return
        val points = JSONArray()
        for (p in g.points) points.put(point(p))
        write(JSONObject().put("type", "route")
            .put("origin", point(g.viewOrigin))
            .put("route_token", g.routeToken)
            .put("generation", g.routeGeneration)
            .put("points", points))
    }

    /** The exact selected MotoMap road scene submitted to BLE. */
    fun scene(s: MotoBLEMapScene) {
        if (!active) return
        val roads = JSONArray()
        for (road in s.roads) {
            val points = JSONArray()
            for (p in road.points) points.put(point(p))
            roads.put(JSONObject().put("road_class", road.roadClass)
                .put("points", points))
        }
        write(JSONObject().put("type", "scene")
            .put("revision", s.sceneRevision)
            .put("roads", roads))
    }

    /** Actual outgoing BLE navigation state, with predicted position used in UI. */
    fun snapshot(s: MotoBLENavigationSnapshot, lat: Double, lon: Double) {
        if (!active || !lat.isFinite() || !lon.isFinite()) return
        write(JSONObject().put("type", "snapshot")
            .put("lat", lat).put("lon", lon)
            .put("state", s.stateName).put("network", s.networkName)
            .put("maneuver", s.maneuverName)
            .put("has_turn", s.maneuverName !in listOf("none", "continue", "arrive") &&
                s.distanceToManeuverM > 0)
            .put("exit", s.roundaboutExit)
            .put("heading_deg", s.headingCentiDegrees / 100.0)
            .put("speed_mps", s.speedDeciKPH / 36.0)
            .put("turn_m", s.distanceToManeuverM)
            .put("remaining_m", s.remainingDistanceM)
            .put("total_m", s.totalDistanceM)
            .put("progress_m", s.routeProgressM)
            .put("duration_s", s.remainingDurationS)
            .put("speed_limit", s.speedLimitKPH)
            .put("route_token", s.routeToken)
            .put("generation", s.routeGeneration))
    }

    fun waypointReached(index: Int, total: Int) {
        if (!active) return
        write(JSONObject().put("type", "waypoint")
            .put("reached", index).put("total", total))
    }

    /**
     * Export from a background thread; returns a private copy under the
     * existing FileProvider diagnostics/exports path.
     * The normal DiagnosticLog.exportZip intentionally excludes this file.
     */
    fun export(context: Context): File {
        val file = requireNotNull(traceFile) { "Registra prima un tracciato GPS" }
        return worker.submit<File> {
            require(file.isFile && file.length() > 0L) { "Tracciato GPS vuoto" }
            val dir = File(context.applicationContext.filesDir, "diagnostics/exports")
            require(dir.mkdirs() || dir.isDirectory)
            val copy = File(dir, "motogps-replay.jsonl")
            file.copyTo(copy, overwrite = true)
            copy
        }.get(20, TimeUnit.SECONDS)
    }
}
