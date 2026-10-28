package com.motogps.android

import com.motogps.android.routing.ValhallaTarValidator
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ValhallaTarValidatorTest {
    @Test fun rejectsEmptyAndNonRoutingArchives() {
        val file = File.createTempFile("valhalla-invalid", ".tar")
        try {
            file.writeBytes(ByteArray(1024))
            assertFalse(ValhallaTarValidator.validateTar(file))
            val bytes = ByteArray(1024)
            "ustar".toByteArray().copyInto(bytes, 257)
            "no-graph.txt".toByteArray().copyInto(bytes, 0)
            "00000000000".toByteArray().copyInto(bytes, 124)
            file.writeBytes(bytes)
            assertFalse(ValhallaTarValidator.validateTar(file))
        } finally {
            file.delete()
        }
    }

    @Test fun acceptsPlainTarContainingGraphTile() {
        val file = File.createTempFile("valhalla-good", ".tar")
        try {
            val bytes = ByteArray(1536)
            "2/000/001.gph".toByteArray().copyInto(bytes, 0)
            "00000000000".toByteArray().copyInto(bytes, 124)
            "ustar".toByteArray().copyInto(bytes, 257)
            file.writeBytes(bytes)
            assertTrue(ValhallaTarValidator.validateTar(file))
        } finally {
            file.delete()
        }
    }
}
