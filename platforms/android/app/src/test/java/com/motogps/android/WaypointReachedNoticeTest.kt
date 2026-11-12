package com.motogps.android

import com.motogps.android.viewmodel.WaypointReachedNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WaypointReachedNoticeTest {
    @Test fun firstIntermediateStopHasBoundedSevenSecondNotice() {
        assertEquals("WP_REACHED:1/3",
            WaypointReachedNotice.message(1, 3, 100_000L, 100_000L))
        assertEquals("WP_REACHED:1/3",
            WaypointReachedNotice.message(1, 3, 100_000L, 106_999L))
        assertNull(WaypointReachedNotice.message(1, 3, 100_000L, 107_000L))
    }

    @Test fun noNoticeForUnreachedStopOrInvalidProgress() {
        assertNull(WaypointReachedNotice.message(0, 2, 100L, 101L))
        assertNull(WaypointReachedNotice.message(3, 2, 100L, 101L))
        assertNull(WaypointReachedNotice.message(1, 2, 0L, 101L))
        assertNull(WaypointReachedNotice.message(1, 2, 200L, 199L))
    }

    @Test fun subsequentStopCreatesDistinctNotice() {
        assertEquals("WP_REACHED:2/3",
            WaypointReachedNotice.message(2, 3, 108_000L, 108_100L))
    }
}
