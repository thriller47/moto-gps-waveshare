package com.motogps.android.ble

import org.junit.Assert.*
import org.junit.Test

class MapSceneAckPolicyTest {
    @Test fun matchingAckConfirmsOnlyCurrentScene() {
        val tracker = MapSceneAckPolicy()
        tracker.begin(41L, 150)
        assertFalse(tracker.acknowledge(149, 0))
        assertNull(tracker.confirmedRevision)
        assertTrue(tracker.acknowledge(150, 0))
        assertEquals(41L, tracker.confirmedRevision)
        assertNull(tracker.sequence)
    }

    @Test fun lateAckAfterReplacementCannotConfirmNewRevision() {
        val tracker = MapSceneAckPolicy()
        tracker.begin(41L, 150)
        tracker.begin(42L, 151)
        assertFalse(tracker.acknowledge(150, 0))
        assertTrue(tracker.acknowledge(151, 4)) // duplicate is already delivered
        assertEquals(42L, tracker.confirmedRevision)
    }

    @Test fun negativeAckDoesNotConfirmAndRetriesAreBounded() {
        val tracker = MapSceneAckPolicy()
        tracker.begin(7L, 33)
        assertFalse(tracker.acknowledge(33, 2))
        assertTrue(tracker.retry())
        assertTrue(tracker.retry())
        assertFalse(tracker.retry())
        assertEquals(3, tracker.attempts)
        assertNull(tracker.confirmedRevision)
    }

    @Test fun reconnectResetsDeliveryState() {
        val tracker = MapSceneAckPolicy()
        tracker.begin(9L, 4)
        tracker.acknowledge(4, 0)
        tracker.reset()
        assertNull(tracker.confirmedRevision)
        assertNull(tracker.sequence)
        assertEquals(0, tracker.attempts)
    }
}
