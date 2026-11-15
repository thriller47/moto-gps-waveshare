package com.motogps.android.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.motogps.android.routing.ValhallaGraphImporter
import org.junit.Test

/** A regression for the staged names: the original .tar.zst suffix is essential. */
class UnifiedOfflineMapImporterTest {
    @Test
    fun keepsOriginalFormatsForEveryStagedComponent() {
        assertEquals("italy-v4.motomap.zip", UnifiedOfflineMapImporter.stagingName("motomap"))
        assertEquals("valhalla_tiles.tar.zst", UnifiedOfflineMapImporter.stagingName("valhalla"))
        assertEquals("photon-italy-v2.sqlite", UnifiedOfflineMapImporter.stagingName("photon"))
        assertTrue(UnifiedOfflineMapImporter.stagingName("valhalla").endsWith(".tar.zst"))
        assertTrue(ValhallaGraphImporter.isSupportedName(UnifiedOfflineMapImporter.stagingName("valhalla")))
    }
}
