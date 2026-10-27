package com.motogps.android

import com.motogps.android.viewmodel.OfflineCoordinateSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfflineCoordinateSearchTest {
    @Test fun acceptsItalianGpsCoordinatesWithoutNetwork() {
        val result = requireNotNull(OfflineCoordinateSearch.parse("45.4642, 9.1900"))
        assertEquals(45.4642, result.latitude, 0.000001)
        assertEquals(9.1900, result.longitude, 0.000001)
        assertEquals("Coordinate GPS (offline)", result.name)
    }

    @Test fun acceptsSouthernAndWesternHemispheres() {
        val result = requireNotNull(OfflineCoordinateSearch.parse(" -12.45 ; -70.8 "))
        assertEquals(-12.45, result.latitude, 0.000001)
        assertEquals(-70.8, result.longitude, 0.000001)
    }

    @Test fun rejectsAddressesAndOutOfRangeCoordinates() {
        assertNull(OfflineCoordinateSearch.parse("Via Roma Milano"))
        assertNull(OfflineCoordinateSearch.parse("91.0, 9.0"))
        assertNull(OfflineCoordinateSearch.parse("45.0, -181.0"))
        assertNull(OfflineCoordinateSearch.parse("45.3"))
    }
}
