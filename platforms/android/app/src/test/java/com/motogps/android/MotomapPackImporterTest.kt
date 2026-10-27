package com.motogps.android

import com.motogps.android.map.MotomapPackImporter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotomapPackImporterTest {
    @Test fun acceptsOnlyExpectedZ15MotomapTiles() {
        assertTrue(MotomapPackImporter.safeName("tile_15_17190_11752.motomap"))
        assertFalse(MotomapPackImporter.safeName("../tile_15_17190_11752.motomap"))
        assertFalse(MotomapPackImporter.safeName("folder/tile_15_17190_11752.motomap"))
        assertFalse(MotomapPackImporter.safeName("tile_14_17190_11752.motomap"))
        assertFalse(MotomapPackImporter.safeName("tile_15_17190_11752.txt"))
        assertFalse(MotomapPackImporter.safeName("tile_15_17190_11752.motomap/evil"))
        assertFalse(MotomapPackImporter.safeName("tile_15_17190_11752.motomap\\\\evil"))
    }
}
