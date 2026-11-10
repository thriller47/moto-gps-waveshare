package com.motogps.android.viewmodel

/** BLE reconnect restores the active route window before its navigation state. */
internal object NavigationReconnectPolicy {
    data class Actions(val geometry: Boolean, val snapshot: Boolean)

    // GATT callbacks use exact connection states. Substring matching would
    // treat "Non connesso" as connected and replay a route into session=0.
    fun isReady(state: String): Boolean = when (state.trim().lowercase()) {
        "connesso", "connected", "ready", "online" -> true
        else -> false
    }

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
