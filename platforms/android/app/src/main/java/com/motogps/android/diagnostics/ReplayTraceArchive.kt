package com.motogps.android.diagnostics

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Coordinates stay private; export only from an explicit user action. */
internal object ReplayTraceArchive {
    fun parts(root: File): List<File> =
        root.listFiles()?.filter {
            it.isFile && it.name.startsWith("replay-current-part-") &&
                it.name.endsWith(".jsonl")
        }?.sortedBy { it.name }.orEmpty().ifEmpty {
            // An old, unsplit recording can still be exported after upgrading.
            listOf(File(root, "replay-current.jsonl")).filter { it.isFile }
        }

    fun export(root: File): File {
        val files = parts(root)
        require(files.isNotEmpty() && files.all { it.length() > 0L }) {
            "Tracciato GPS vuoto: avvia prima una registrazione"
        }
        val dir = File(root, "exports")
        require(dir.mkdirs() || dir.isDirectory)
        if (files.size == 1) {
            val copy = File(dir, "motogps-replay.jsonl")
            files.first().copyTo(copy, overwrite = true)
            return copy
        }
        val zipFile = File(dir, "motogps-replay-parts.zip")
        ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
            for (file in files) {
                zip.putNextEntry(ZipEntry(
                    file.name.replace("replay-current", "motogps-replay")))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("README.txt"))
            zip.write(("Moto GPS private replay. Contains GPS coordinates.\n" +
                "Unzip and load each numbered .jsonl in chronological order. " +
                "Each part starts at t_ms=0.\n" +
                "Files stay private until you explicitly share them.\n")
                .toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return zipFile
    }
}
