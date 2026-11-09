package com.motogps.android

import com.motogps.android.viewmodel.NavigationReconnectPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationReconnectPolicyTest {
    @Test fun runningRouteRestoresGeometryAndSnapshot() {
        assertEquals(NavigationReconnectPolicy.Actions(true, true),
            NavigationReconnectPolicy.actions(true, false, true, true))
    }

    @Test fun idleConnectionNeverReplaysPreviousNavigation() {
        assertEquals(NavigationReconnectPolicy.Actions(false, false),
            NavigationReconnectPolicy.actions(false, false, true, true))
    }

    @Test fun routeWithNoGeometryStillRestoresSnapshot() {
        assertEquals(NavigationReconnectPolicy.Actions(false, true),
            NavigationReconnectPolicy.actions(true, false, true, false))
    }

    @Test fun arrivedRouteRetainsArrivalStateWithoutReplayingGeometry() {
        assertEquals(NavigationReconnectPolicy.Actions(false, true),
            NavigationReconnectPolicy.actions(true, true, true, true))
    }

    @Test fun missingSnapshotNeverReplaysOrphanGeometry() {
        assertEquals(NavigationReconnectPolicy.Actions(false, false),
            NavigationReconnectPolicy.actions(true, false, false, true))
    }
}
