package com.motogps.android

import com.motogps.android.map.MotomapPackImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotomapPackImporterTest {
    @Test fun manifestRequiresExplicitV4JunctionContract() {
        assertTrue(MotomapPackImporter.isV4Manifest(
            """{"format":"motomap","version":4,"zoom":15,"topology":"osm-node-ids-base36","junction":"osm-junction-way-v4"}"""))
        assertFalse(MotomapPackImporter.isV4Manifest(
            """{"format":"motomap","version":3,"zoom":15,"topology":"osm-node-ids-base36"}"""))
        assertFalse(MotomapPackImporter.isV4Manifest(
            """{"format":"motomap","version":4,"zoom":15,"topology":"osm-node-ids-base36"}"""))
    }

    @Test fun acceptsOnlyExpectedZ15MotomapTiles() {
        assertTrue(MotomapPackImporter.safeName("tile_15_17190_11752.motomap"))
        assertFalse(MotomapPackImporter.safeName("motomap_v2.json"))
        assertFalse(MotomapPackImporter.safeName("motomap_v3.json"))
        assertTrue(MotomapPackImporter.safeName("motomap_v4.json"))
        assertEquals("motomap_v4.json", MotomapPackImporter.METADATA)
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
