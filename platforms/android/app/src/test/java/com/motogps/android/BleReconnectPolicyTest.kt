package com.motogps.android

import com.motogps.android.ble.BleReconnectPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class BleReconnectPolicyTest {
    @Test fun retryDelayIncreasesWithoutUnboundedPolling() {
        assertEquals(1_500L, BleReconnectPolicy.delayMillis(0))
        assertEquals(3_000L, BleReconnectPolicy.delayMillis(1))
        assertEquals(6_000L, BleReconnectPolicy.delayMillis(2))
        assertEquals(12_000L, BleReconnectPolicy.delayMillis(3))
        assertEquals(30_000L, BleReconnectPolicy.delayMillis(4))
        assertEquals(30_000L, BleReconnectPolicy.delayMillis(100))
    }

    @Test fun recoveryAlternatesKnownDeviceAndFilteredScan() {
        assertEquals(true, BleReconnectPolicy.useSystemConnect(true, true))
        assertEquals(false, BleReconnectPolicy.useSystemConnect(true, false))
        assertEquals(false, BleReconnectPolicy.useSystemConnect(false, true))
        assertEquals(false, BleReconnectPolicy.useSystemConnect(false, false))
    }

    @Test fun pendingGattAndHandshakeHaveFiniteTimeouts() {
        assertEquals(30_000L, BleReconnectPolicy.SYSTEM_CONNECT_WINDOW_MS)
        assertEquals(20_000L, BleReconnectPolicy.HANDSHAKE_TIMEOUT_MS)
    }

    @Test fun scanWindowIsLimited() {
        assertEquals(12_000L, BleReconnectPolicy.SCAN_WINDOW_MS)
        assertEquals(1_500L, BleReconnectPolicy.delayMillis(-1))
    }
}
