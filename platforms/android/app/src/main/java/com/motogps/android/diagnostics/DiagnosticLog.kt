package com.motogps.android.diagnostics

import android.content.Context
import android.os.SystemClock
import com.motogps.android.jni.MotoBLEDiagnosticRecord
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Persistent, bounded road-test trace. No GPS coordinates or road names. */
object DiagnosticLog {
    // Two rolling files per source: at most ~6 MiB altogether, plus export.
    // Keep a useful one-hour joint Android/ESP32 trace without unbounded growth.
    private const val MAX_ANDROID_FILE_BYTES = 1L * 1024L * 1024L
    private const val MAX_ESP32_FILE_BYTES = 2L * 1024L * 1024L
    private val worker = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(2048),
        { runnable -> Thread(runnable, "MotoGpsTrace").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy()
    )
    @Volatile private var root: File? = null
    private val timeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
    private var androidWriter: BufferedWriter? = null
    private var androidFile: File? = null
    private var esp32Writer: BufferedWriter? = null
    private var esp32File: File? = null

    private fun closeWriters() {
        runCatching { androidWriter?.flush(); androidWriter?.close() }
        androidWriter = null
        androidFile = null
        runCatching { esp32Writer?.flush(); esp32Writer?.close() }
        esp32Writer = null
        esp32File = null
    }

    private fun rotateIfNeeded(current: File, previousName: String, maxBytes: Long, isEsp32: Boolean) {
        if (current.length() < maxBytes) return
        if (isEsp32) {
            runCatching { esp32Writer?.flush(); esp32Writer?.close() }
            esp32Writer = null
            esp32File = null
        } else {
            runCatching { androidWriter?.flush(); androidWriter?.close() }
            androidWriter = null
            androidFile = null
        }
        val previous = File(current.parentFile, previousName)
        if (previous.exists()) previous.delete()
        if (!current.renameTo(previous)) {
            runCatching { current.writeText("") }
        }
    }

    fun init(context: Context) {
        val dir = File(context.filesDir, "diagnostics")
        if (dir.mkdirs() || dir.isDirectory) {
            root = dir
            event("APP", "started")
        }
    }

    fun event(type: String, details: String = "") {
        val dir = root ?: return
        val wall = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val safe = details.replace('\n', ' ').replace('\r', ' ').take(240)
        worker.execute {
            try {
                val current = File(dir, "current.log")
                rotateIfNeeded(current, "previous.log", MAX_ANDROID_FILE_BYTES, isEsp32 = false)
                if (androidWriter == null || androidFile != current) {
                    runCatching { androidWriter?.close() }
                    androidWriter = BufferedWriter(FileWriter(current, true), 8192)
                    androidFile = current
                }
                val stamp = Instant.ofEpochMilli(wall).atZone(ZoneId.systemDefault()).format(timeFormatter)
                androidWriter!!.write("$stamp wall_ms=$wall elapsed_ms=$elapsed $type $safe\n")
                androidWriter!!.flush()
            } catch (_: Exception) {
                // Diagnostics must never interrupt navigation, BLE or GPS.
            }
        }
    }

    /** Never block the BLE callback with file I/O. */
    fun esp32Batch(records: List<MotoBLEDiagnosticRecord>) {
        val dir = root ?: return
        if (records.isEmpty()) return
        val wall = System.currentTimeMillis()
        worker.execute {
            try {
                val current = File(dir, "esp32-current.log")
                rotateIfNeeded(current, "esp32-previous.log", MAX_ESP32_FILE_BYTES, isEsp32 = true)
                if (esp32Writer == null || esp32File != current) {
                    runCatching { esp32Writer?.close() }
                    esp32Writer = BufferedWriter(FileWriter(current, true), 8192)
                    esp32File = current
                }
                esp32Writer!!.write(records.joinToString("") { record ->
                    "wall_ms=$wall esp32_uptime_ms=${record.uptimeMs} " +
                        "seq=${record.sequence} event=${record.event} " +
                        "v=${record.values.joinToString(",")}\n"
                })
                esp32Writer!!.flush()
            } catch (_: Exception) {
                // Never interrupt BLE or navigation because of diagnostics.
            }
        }
    }

    /**
     * A single explicitly shared archive for Android, ESP32 and the complete
     * GPS replay session. Must run off main thread after recording is stopped.
     *
     * The two exporters flush their respective single-thread workers before
     * their snapshots are read. Replay parts are flattened into this archive,
     * so each JSONL can be loaded directly by the browser simulator.
     */
    fun exportCompleteZip(context: Context): File {
        val replay = ReplayTraceRecorder.export(context)
        val logs = exportZip()
        val dir = requireNotNull(root) { "Diagnostic logger not initialized" }
        val exportDir = File(dir, "exports").apply { mkdirs() }
        val complete = File(exportDir, "motogps-diagnostica-completa.zip")
        val staged = File(exportDir, "motogps-diagnostica-completa.tmp")
        try {
            ZipOutputStream(staged.outputStream().buffered()).use { zip ->
                ZipFile(logs).use { logArchive ->
                    for (entryName in listOf(
                            "previous.log", "current.log",
                            "esp32-previous.log", "esp32-current.log")) {
                        val entry = logArchive.getEntry(entryName) ?: continue
                        zip.putNextEntry(ZipEntry("logs/" + entryName))
                        logArchive.getInputStream(entry).use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
                if (replay.extension.equals("zip", ignoreCase = true)) {
                    ZipFile(replay).use { replayArchive ->
                        val entries = replayArchive.entries()
                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            if (entry.isDirectory || !entry.name.endsWith(".jsonl")) continue
                            // All names are generated internally, not from user input.
                            val name = entry.name.substringAfterLast('/')
                            zip.putNextEntry(ZipEntry("gps/" + name))
                            replayArchive.getInputStream(entry).use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                } else {
                    zip.putNextEntry(ZipEntry("gps/motogps-replay.jsonl"))
                    replay.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry("README.txt"))
                zip.write((
                    "Moto Chat GPS - diagnostica completa privata.\n" +
                    "logs/: eventi Android e ESP32 ricevuti via BLE.\n" +
                    "gps/: tracce JSONL complete con coordinate, percorsi e velocita'.\n" +
                    "Caricare le parti JSONL nel simulatore in ordine numerico.\n" +
                    "Le coordinate sono informazioni personali: condividere solo volontariamente.\n"
                ).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            try {
                Files.move(
                    staged.toPath(), complete.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: Exception) {
                Files.move(staged.toPath(), complete.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            return complete
        } finally {
            if (staged.exists()) staged.delete()
            if (logs.exists() && logs != complete) logs.delete()
            if (replay.exists() && replay != complete && replay.parentFile?.name == "exports") {
                replay.delete()
            }
        }
    }

    /** Call from a background thread; waits for pending log events first. */
    fun exportZip(): File {
        val dir = requireNotNull(root) { "Diagnostic logger not initialized" }
        return worker.submit<File> {
            closeWriters()
            val exportDir = File(dir, "exports").apply { mkdirs() }
            val zipFile = File(exportDir, "motogps-android-diagnostics.zip")
            val staged = File(exportDir, "motogps-android-diagnostics.tmp")
            try {
                ZipOutputStream(staged.outputStream().buffered()).use { zip ->
                    for (name in listOf("previous.log", "current.log", "esp32-previous.log", "esp32-current.log")) {
                        val file = File(dir, name)
                        if (file.isFile) {
                            zip.putNextEntry(ZipEntry(name))
                            file.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                    zip.putNextEntry(ZipEntry("README.txt"))
                    zip.write("MotoGPS Android persistent trace. Sensitive timing, route and speed metadata.\n".toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
                Files.move(staged.toPath(), zipFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                zipFile
            } finally {
                if (staged.exists()) staged.delete()
            }
        }.get()
    }
}
