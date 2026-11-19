package com.motogps.android.diagnostics

import android.content.Context
import android.os.SystemClock
import com.motogps.android.jni.MotoBLEDiagnosticRecord
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Persistent, bounded road-test trace. No GPS coordinates or road names. */
object DiagnosticLog {
    // Two rolling files per source: at most ~6 MiB altogether, plus export.
    // Keep a useful one-hour joint Android/ESP32 trace without unbounded growth.
    private const val MAX_ANDROID_FILE_BYTES = 1L * 1024L * 1024L
    private const val MAX_ESP32_FILE_BYTES = 2L * 1024L * 1024L
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "MotoGpsTrace").apply { isDaemon = true }
    }
    @Volatile private var root: File? = null
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT)
        .apply { timeZone = TimeZone.getDefault() }

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
                if (current.length() >= MAX_ANDROID_FILE_BYTES) {
                    val previous = File(dir, "previous.log")
                    if (previous.exists()) previous.delete()
                    current.renameTo(previous)
                }
                val stamp = timeFormat.format(Date(wall))
                current.appendText("$stamp wall_ms=$wall elapsed_ms=$elapsed $type $safe\n")
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
                if (current.length() >= MAX_ESP32_FILE_BYTES) {
                    val previous = File(dir, "esp32-previous.log")
                    if (previous.exists()) previous.delete()
                    current.renameTo(previous)
                }
                current.appendText(records.joinToString("") { record ->
                    "wall_ms=$wall esp32_uptime_ms=${record.uptimeMs} " +
                        "seq=${record.sequence} event=${record.event} " +
                        "v=${record.values.joinToString(",")}\n"
                })
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
        val exportDir = File(dir, "exports")
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
                    "Moto Chat GPS - diagnostica completa privata.\\n" +
                    "logs/: eventi Android e ESP32 ricevuti via BLE.\\n" +
                    "gps/: tracce JSONL complete con coordinate, percorsi e velocita'.\\n" +
                    "Caricare le parti JSONL nel simulatore in ordine numerico.\\n" +
                    "Le coordinate sono informazioni personali: condividere solo volontariamente.\\n"
                ).replace("\\\\n", "\\n").toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            if (complete.exists() && !complete.delete()) {
                throw IllegalStateException("Impossibile sostituire l'archivio precedente")
            }
            check(staged.renameTo(complete)) { "Impossibile finalizzare l'archivio" }
            return complete
        } finally {
            if (staged.exists()) staged.delete()
        }
    }

    /** Call from a background thread; waits for pending log events first. */
    fun exportZip(): File {
        val dir = requireNotNull(root) { "Diagnostic logger not initialized" }
        return worker.submit<File> {
            val exportDir = File(dir, "exports").apply { mkdirs() }
            val zipFile = File(exportDir, "motogps-android-diagnostics.zip")
            ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
                for (name in listOf("previous.log", "current.log", "esp32-previous.log", "esp32-current.log")) {
                    val file = File(dir, name)
                    if (file.isFile) {
                        zip.putNextEntry(ZipEntry(name))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
                zip.putNextEntry(ZipEntry("README.txt"))
                zip.write("MotoGPS Android persistent trace. Sensitive timing, route and speed metadata.\n".toByteArray())
                zip.closeEntry()
            }
            zipFile
        }.get()
    }
}
