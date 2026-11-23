package com.motogps.android.diagnostics

import android.content.Context
import android.os.SystemClock
import com.motogps.android.jni.GeoPointE6
import com.motogps.android.jni.MotoBLEMapScene
import com.motogps.android.jni.MotoBLENavigationSnapshot
import com.motogps.android.jni.MotoBLERouteGeometry
import com.motogps.android.routing.ValhallaTurnGuidance
import com.motogps.android.viewmodel.RouteProgressMatcher
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
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
    private data class ActiveSession(val id: Long, val startElapsedMs: Long)

    private val worker = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(2048),
        { job -> Thread(job, "MotoGpsPrivateReplay").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy()
    )
    @Volatile private var activeSession: ActiveSession? = null
    private var nextSessionId = 0L
    private var workerSessionId = -1L
    private var workerRoot: File? = null
    private var sessionWallMs = 0L
    private var partStartedAtMs = 0L
    private var partIndex = 0
    private var partFile: File? = null
    private var partOut: BufferedOutputStream? = null
    private var partBytesWritten = 0L

    private fun closePartStream() {
        runCatching { partOut?.flush(); partOut?.close() }
        partOut = null
    }
    // Carry the last visible route, scene and snapshot into every new part.
    // Otherwise a file opened on its own could start without map context.
    private var lastRouteFullEvent: String? = null
    @Volatile private var lastFullRouteKey: String? = null
    private var lastRouteEvent: String? = null
    private var lastSceneEvent: String? = null
    private var lastSnapshotEvent: String? = null

    fun isRecording(): Boolean = activeSession != null

    @Synchronized fun start(context: Context) {
        if (activeSession != null) return
        val root = File(context.applicationContext.filesDir, "diagnostics")
        require(root.mkdirs() || root.isDirectory) { "Directory diagnostica non disponibile" }
        val id = ++nextSessionId
        val capturedWallMs = System.currentTimeMillis()
        val startedAt = SystemClock.elapsedRealtime()
        activeSession = ActiveSession(id, startedAt)
        lastFullRouteKey = null
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
                closePartStream()
                partIndex = 0
                partFile = null
                partBytesWritten = 0L
                lastRouteFullEvent = null
                lastRouteEvent = null
                lastSceneEvent = null
                lastSnapshotEvent = null
                startPart(0L, incomingType = null)
            } catch (e: Exception) {
                fail(id, e)
            }
        }
    }

    @Synchronized fun stop() {
        activeSession = null
        worker.execute { closePartStream() }
    }

    private fun startPart(elapsedMs: Long, incomingType: String?) {
        closePartStream()
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
        val out = BufferedOutputStream(FileOutputStream(file, false), 16 * 1024)
        var written = 0L
        fun writeRawLine(line: String) {
            val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
            out.write(bytes)
            written += bytes.size
        }
        writeRawLine(header.toString())
        val carried = listOf(
            "route_full" to lastRouteFullEvent,
            "route" to lastRouteEvent,
            "scene" to lastSceneEvent,
            "snapshot" to lastSnapshotEvent
        )
        for ((type, event) in carried) {
            if (event != null && type != incomingType) {
                writeRawLine(event)
            }
        }
        out.flush()
        partFile = file
        partOut = out
        partBytesWritten = written
    }

    @Synchronized private fun fail(id: Long, exception: Exception) {
        if (activeSession?.id == id) activeSession = null
        closePartStream()
        DiagnosticLog.event("GPS_REPLAY_ERROR", exception.javaClass.simpleName)
    }

    private fun point(p: GeoPointE6): JSONArray =
        JSONArray().put(p.latitudeE6 / 1e6).put(p.longitudeE6 / 1e6)

    private fun enqueueWrite(buildEvent: () -> JSONObject) {
        val session = activeSession ?: return
        val elapsedMs = SystemClock.elapsedRealtime() - session.startElapsedMs
        if (elapsedMs < 0L) return
        val id = session.id
        worker.execute {
            if (workerSessionId != id) return@execute
            try {
                val data = buildEvent()
                val eventType = data.optString("type")
                var out = requireNotNull(partOut)
                data.put("t_ms", (elapsedMs - partStartedAtMs).coerceAtLeast(0L))
                var lineBytes = (data.toString() + "\n").toByteArray(Charsets.UTF_8)
                if (ReplayTraceSegmentPolicy.rotate(
                        elapsedMs, partStartedAtMs, partBytesWritten,
                        lineBytes.size)) {
                    startPart(elapsedMs, incomingType = eventType)
                    out = requireNotNull(partOut)
                    data.put("t_ms", 0L)
                    lineBytes = (data.toString() + "\n").toByteArray(Charsets.UTF_8)
                }
                out.write(lineBytes)
                out.flush()
                partBytesWritten += lineBytes.size
                when (eventType) {
                    "route_full" -> { data.put("t_ms", 0L); lastRouteFullEvent = data.toString() }
                    "route" -> { data.put("t_ms", 0L); lastRouteEvent = data.toString() }
                    "scene" -> { data.put("t_ms", 0L); lastSceneEvent = data.toString() }
                    "snapshot" -> { data.put("t_ms", 0L); lastSnapshotEvent = data.toString() }
                }
            } catch (e: Exception) {
                fail(id, e)
            }
        }
    }

    /** Accepted raw GNSS fix; used later for true Android rerun, not the LVGL replay. */
    fun fix(lat: Double, lon: Double, speedMps: Float,
            headingDeg: Float?, accuracyM: Float) {
        if (activeSession == null || !lat.isFinite() || !lon.isFinite()) return
        enqueueWrite {
            val e = JSONObject().put("type", "fix").put("lat", lat).put("lon", lon)
                .put("speed_mps", if (speedMps.isFinite()) speedMps.toDouble() else 0.0)
                .put("accuracy_m", if (accuracyM.isFinite()) accuracyM.toDouble() else -1.0)
            if (headingDeg != null && headingDeg.isFinite())
                e.put("heading_deg", headingDeg.toDouble())
            e
        }
    }

    @Synchronized internal fun fullRoute(
        g: MotoBLERouteGeometry, plan: ValhallaTurnGuidance.Plan
    ) {
        val session = activeSession ?: return
        if (g.points.size < 2 || g.points.size > 150_000) return
        val key = "${session.id}:${g.routeToken}:${g.routeGeneration}"
        if (key == lastFullRouteKey) return
        lastFullRouteKey = key
        enqueueWrite { ReplayProgressPayload.fullRoute(g, plan) }
    }

    internal fun progress(
        lat: Double, lon: Double,
        routeToken: Long, generation: Long, speedMps: Float,
        travelHeading: Float?, rawRemainingM: Long,
        guardedRemainingM: Long,
        match: RouteProgressMatcher.MatchEvidence?,
        next: ValhallaTurnGuidance.Plan.Next?,
        totalShapeM: Double
    ) {
        if (activeSession == null || !lat.isFinite() || !lon.isFinite()) return
        enqueueWrite {
            ReplayProgressPayload.progress(
                lat, lon, routeToken, generation, speedMps, travelHeading,
                rawRemainingM, guardedRemainingM, match, next, totalShapeM)
        }
    }

    fun route(g: MotoBLERouteGeometry) {
        if (activeSession == null) return
        enqueueWrite {
            val points = JSONArray()
            for (p in g.points) points.put(point(p))
            JSONObject().put("type", "route")
                .put("origin", point(g.viewOrigin))
                .put("route_token", g.routeToken)
                .put("generation", g.routeGeneration)
                .put("points", points)
        }
    }

    fun scene(s: MotoBLEMapScene) {
        if (activeSession == null) return
        enqueueWrite {
            val roads = JSONArray()
            for (road in s.roads) {
                val points = JSONArray()
                for (p in road.points) points.put(point(p))
                roads.put(JSONObject().put("road_class", road.roadClass)
                    .put("points", points))
            }
            JSONObject().put("type", "scene")
                .put("revision", s.sceneRevision)
                .put("roads", roads)
        }
    }

    fun snapshot(s: MotoBLENavigationSnapshot, lat: Double, lon: Double) {
        if (activeSession == null || !lat.isFinite() || !lon.isFinite()) return
        enqueueWrite {
            val hasTurn = s.maneuverName !in setOf("none", "continue") &&
                (s.distanceToManeuverM > 0 || s.maneuverName == "roundabout" || s.maneuverName == "arrive")
            JSONObject().put("type", "snapshot")
                .put("lat", lat).put("lon", lon)
                .put("position_source", "accepted_gps")
                .put("state", s.stateName).put("network", s.networkName)
                .put("maneuver", s.maneuverName)
                .put("has_turn", hasTurn)
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
                .put("generation", s.routeGeneration)
        }
    }

    fun waypointReached(index: Int, total: Int) {
        if (activeSession == null) return
        enqueueWrite {
            JSONObject().put("type", "waypoint")
                .put("reached", index).put("total", total)
        }
    }

    fun export(context: Context): File {
        val root = File(context.applicationContext.filesDir, "diagnostics")
        return worker.submit<File> {
            closePartStream()
            ReplayTraceArchive.export(root)
        }.get()
    }
}
