package com.motogps.android

import com.motogps.android.diagnostics.ReplayTraceArchive
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReplayTraceArchiveTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun exportsSinglePartAsPlainJsonl() {
        val root = temporary.newFolder("one")
        val payload = "{\"type\":\"meta\",\"t_ms\":0}\n"
        File(root, "replay-current-part-00001.jsonl").writeText(payload)
        val exported = ReplayTraceArchive.export(root)
        assertEquals("motogps-replay.jsonl", exported.name)
        assertEquals(payload, exported.readText())
    }

    @Test fun exportsEveryPartInChronologicalZip() {
        val root = temporary.newFolder("multiple")
        File(root, "replay-current-part-00002.jsonl")
            .writeText("second event\n")
        File(root, "replay-current-part-00001.jsonl")
            .writeText("first event\n")
        val output = ReplayTraceArchive.export(root)
        assertEquals("motogps-replay-parts.zip", output.name)
        ZipFile(output).use { zip ->
            val names = mutableListOf<String>()
            val entries = zip.entries()
            while (entries.hasMoreElements()) names += entries.nextElement().name
            assertEquals(listOf("motogps-replay-part-00001.jsonl",
                "motogps-replay-part-00002.jsonl", "README.txt"), names)
            assertEquals("first event\n", zip.getInputStream(
                zip.getEntry(names[0])).bufferedReader().use { it.readText() })
            assertEquals("second event\n", zip.getInputStream(
                zip.getEntry(names[1])).bufferedReader().use { it.readText() })
        }
    }

    @Test fun preservesLegacySingleFileAndRejectsEmptyRecordings() {
        val root = temporary.newFolder("legacy")
        assertThrows(IllegalArgumentException::class.java) {
            ReplayTraceArchive.export(root)
        }
        val legacy = File(root, "replay-current.jsonl")
        legacy.writeText("old trace\n")
        assertEquals("old trace\n", ReplayTraceArchive.export(root).readText())
    }
}
