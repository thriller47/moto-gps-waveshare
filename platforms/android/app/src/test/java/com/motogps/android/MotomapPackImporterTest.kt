package com.motogps.android

import com.motogps.android.map.MotomapPackImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotomapPackImporterTest {
    @Test fun acceptsOnlyExpectedZ15MotomapTiles() {
        assertTrue(MotomapPackImporter.safeName("tile_15_17190_11752.motomap"))
        assertTrue(MotomapPackImporter.safeName("motomap_v2.json"))
        assertTrue(MotomapPackImporter.safeName("motomap_v3.json"))
        assertEquals("motomap_v3.json", MotomapPackImporter.METADATA)
        assertTrue(MotomapPackImporter.safeName("coverage_z14.txt"))
        assertFalse(MotomapPackImporter.safeName("../coverage_z14.txt"))
        assertFalse(MotomapPackImporter.safeName("../tile_15_17190_11752.motomap"))
        assertFalse(MotomapPackImporter.safeName("folder/tile_15_17190_11752.motomap"))
        assertFalse(MotomapPackImporter.safeName("tile_14_17190_11752.motomap"))
        assertFalse(MotomapPackImporter.safeName("tile_15_17190_11752.txt"))
        assertFalse(MotomapPackImporter.safeName("tile_15_17190_11752.motomap/evil"))
        assertFalse(MotomapPackImporter.safeName("tile_15_17190_11752.motomap\\\\evil"))
    }
}
