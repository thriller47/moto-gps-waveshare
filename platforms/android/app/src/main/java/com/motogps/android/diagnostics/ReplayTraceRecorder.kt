package com.motogps.android.diagnostics

import android.content.Context
import android.os.SystemClock
import com.motogps.android.jni.GeoPointE6
import com.motogps.android.jni.MotoBLEMapScene
import com.motogps.android.jni.MotoBLENavigationSnapshot
import com.motogps.android.jni.MotoBLERouteGeometry
import java.io.File
import java.util.concurrent.Executors
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Explicitly enabled, private location/geometry recording for Windows replay.
 *
 * Each file is bounded by size/time, but the session is not: a long ride
 * automatically creates sequential replay-current-part-*.jsonl files.
 * GPS coordinates NEVER enter DiagnosticLog's ordinary ZIP. File I/O runs
 * exclusively on one private worker and never blocks the GPS/BLE callbacks.
 */
object ReplayTraceRecorder {
    private val worker = Executors.newSingleThreadExecutor { job ->
        Thread(job, "MotoGpsPrivateReplay").apply { isDaemon = true }
    }
    @Volatile private var active = false
    @Volatile private var startElapsed = 0L
    @Volatile private var sessionId = 0L
    private var workerSessionId = -1L
    private var workerRoot: File? = null
    private var sessionWallMs = 0L
    private var partStartedAtMs = 0L
    private var partIndex = 0
    private var partFile: File? = null
    // Carry the last visible route, scene and snapshot into every new part.
    // Otherwise a file opened on its own could start without map context.
    private var lastRouteEvent: String? = null
    private var lastSceneEvent: String? = null
    private var lastSnapshotEvent: String? = null

    fun isRecording(): Boolean = active

    @Synchronized fun start(context: Context) {
        if (active) return
        val root = File(context.applicationContext.filesDir, "diagnostics")
        require(root.mkdirs() || root.isDirectory) { "Directory diagnostica non disponibile" }
        val id = sessionId + 1L
        sessionId = id
        val capturedWallMs = System.currentTimeMillis()
        startElapsed = SystemClock.elapsedRealtime()
        active = true
        worker.execute {
            try {
                // Like the old replay-current.jsonl, a new recording replaces
                // the prior unexported session. Exports are kept separately.
                root.listFiles()?.filter {
                    it.name == "replay-current.jsonl" ||
                        (it.name.startsWith("replay-current-part-") &&
                            it.name.endsWith(".jsonl"))
                }?.forEach { if (it.isFile) check(it.delete()) }
                workerSessionId = id
                workerRoot = root
                sessionWallMs = capturedWallMs
                partIndex = 0
                partFile = null
                lastRouteEvent = null
                lastSceneEvent = null
                lastSnapshotEvent = null
                startPart(0L)
            } catch (e: Exception) {
                fail(id, e)
            }
        }
    }

    @Synchronized fun stop() { active = false }

    private fun startPart(elapsedMs: Long) {
        val root = requireNotNull(workerRoot)
        ++partIndex
        partStartedAtMs = elapsedMs
        val file = File(root, "replay-current-part-" +
            String.format(Locale.ROOT, "%05d", partIndex) + ".jsonl")
        val header = JSONObject()
            .put("schema", "moto-replay-v1")
            .put("type", "meta")
            .put("t_ms", 0)
            .put("source", "Android-BLE-outgoing")
            .put("captured_at_ms", sessionWallMs + elapsedMs)
            .put("part_index", partIndex)
            .put("session_elapsed_ms", elapsedMs)
        val prefix = StringBuilder(header.toString()).append('\n')
        // Every part can be replayed independently, including one starting
        // halfway through a curve or immediately before a reroute.
        for (event in listOfNotNull(lastRouteEvent, lastSceneEvent, lastSnapshotEvent)) {
            prefix.append(JSONObject(event).put("t_ms", 0L).toString()).append('\n')
        }
        file.writeText(prefix.toString())
        partFile = file
    }

    private fun fail(id: Long, exception: Exception) {
        if (sessionId == id) active = false
        // DiagnosticLog is coordinate-free; record only the failure type.
        DiagnosticLog.event("GPS_REPLAY_ERROR", exception.javaClass.simpleName)
    }

    private fun point(p: GeoPointE6): JSONArray =
        JSONArray().put(p.latitudeE6 / 1e6).put(p.longitudeE6 / 1e6)

    private fun write(data: JSONObject) {
        if (!active) return
        val id = sessionId
        val elapsedMs = SystemClock.elapsedRealtime() - startElapsed
        if (elapsedMs < 0L) return
        // Never stop at 60 minutes or 24 MiB: only the *current part* rotates.
        worker.execute {
            if (workerSessionId != id) return@execute
            try {
                var file = requireNotNull(partFile)
                data.put("t_ms", (elapsedMs - partStartedAtMs).coerceAtLeast(0L))
                var line = data.toString() + "\n"
                if (ReplayTraceSegmentPolicy.rotate(
                        elapsedMs, partStartedAtMs, file.length(),
                        line.toByteArray(Charsets.UTF_8).size)) {
                    startPart(elapsedMs)
                    file = requireNotNull(partFile)
                    data.put("t_ms", 0L)
                    line = data.toString() + "\n"
                }
                file.appendText(line)
                // Update the replay context only after the event was
                // successfully written, never from a discarded queue entry.
                when (data.optString("type")) {
                    "route" -> lastRouteEvent = data.toString()
                    "scene" -> lastSceneEvent = data.toString()
                    "snapshot" -> lastSnapshotEvent = data.toString()
                }
            } catch (e: Exception) {
                fail(id, e)
            }
        }
    }

    /** Accepted raw GNSS fix; used later for true Android rerun, not the LVGL replay. */
    fun fix(lat: Double, lon: Double, speedMps: Float,
            headingDeg: Float?, accuracyM: Float) {
        if (!active || !lat.isFinite() || !lon.isFinite()) return
        val e = JSONObject().put("type", "fix").put("lat", lat).put("lon", lon)
            .put("speed_mps", if (speedMps.isFinite()) speedMps.toDouble() else 0.0)
            .put("accuracy_m", if (accuracyM.isFinite()) accuracyM.toDouble() else -1.0)
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

    /** Outgoing BLE state paired with the last accepted GPS fix (not predicted).
     * Old replay files may contain extrapolated snapshot positions; newly
     * recorded snapshots explicitly label their coordinate source.
     */
    fun snapshot(s: MotoBLENavigationSnapshot, lat: Double, lon: Double) {
        if (!active || !lat.isFinite() || !lon.isFinite()) return
        write(JSONObject().put("type", "snapshot")
            .put("lat", lat).put("lon", lon)
            .put("position_source", "accepted_gps")
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
     * Export after pending events on the same worker. Large ZIPs are streamed,
     * so an arbitrarily long recording does not require a large RAM buffer.
     * Caller must invoke this from a background thread, never the UI.
     */
    fun export(context: Context): File {
        val root = File(context.applicationContext.filesDir, "diagnostics")
        return worker.submit<File> { ReplayTraceArchive.export(root) }.get()
    }
}
