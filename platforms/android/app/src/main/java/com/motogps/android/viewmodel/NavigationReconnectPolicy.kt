package com.motogps.android.viewmodel

/** BLE reconnect restores the active route window before its navigation state. */
internal object NavigationReconnectPolicy {
    data class Actions(val geometry: Boolean, val snapshot: Boolean)

    fun actions(
        navigating: Boolean,
        arrived: Boolean,
        hasSnapshot: Boolean,
        hasGeometry: Boolean
    ): Actions {
        if (!navigating || !hasSnapshot) return Actions(geometry = false, snapshot = false)
        return Actions(geometry = hasGeometry && !arrived, snapshot = true)
    }
}
