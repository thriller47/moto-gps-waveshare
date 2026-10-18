package com.motogps.android.jni

data class MotoBLEInboundMessage(
    val sequence: Int,
    val ackRequested: Boolean,
    val complete: Boolean,
    val duplicate: Boolean,
    val connectionStatus: MotoBLEConnectionStatus?,
    val heartbeat: MotoBLEHeartbeat?,
    val acknowledgement: MotoBLEAcknowledgement?,
    val deviceCommand: MotoBLEDeviceCommand?
)

data class MotoBLEConnectionStatus(
    val role: Int,
    val state: Int,
    val minimumVersion: Int,
    val maximumVersion: Int,
    val capabilities: Long,
    val sessionId: Long,
    val maxFrameSize: Int,
    val heartbeatIntervalMs: Int = 1000
)

data class MotoBLEHeartbeat(
    val sessionId: Long
)

data class MotoBLEAcknowledgement(
    val sequence: Int,
    val status: Int,
    val commandId: Int
)

data class MotoBLEDeviceCommand(
    val sequence: Int,
    val ackRequested: Boolean,
    val kind: Int,
    val commandId: Int,
    val page: Int,
    val x: Int,
    val y: Int,
    val eventTimeMs: Long
)

data class MotoBLENavigationSnapshot(
    val stateName: String = "idle",
    val networkName: String = "online",
    val displayPageName: String = "navigation",
    val maneuverName: String = "straight",
    val trafficName: String = "clear",
    val hasDestination: Boolean = false,
    val hasFix: Boolean = true,
    val gnssStale: Boolean = false,
    val offRoute: Boolean = false,
    val routeRequestInFlight: Boolean = false,
    val trafficRequestInFlight: Boolean = false,
    val routeToken: Long = 0L,
    val routeGeneration: Long = 0L,
    val maneuverID: Long = 0L,
    val distanceToManeuverM: Long = 0L,
    val remainingDistanceM: Long = 0L,
    val remainingDurationS: Long = 0L,
    val routeProgressM: Long = 0L,
    val totalDistanceM: Long = 0L,
    val speedDeciKPH: Int = 0,
    val speedLimitKPH: Int = 0,
    val headingCentiDegrees: Int = 0,
    val accuracyDecimeters: Int = 0,
    val crossTrackDecimeters: Int = 0,
    val roundaboutExit: Int = 0,
    val instructionText: String = "Continua su"
)

data class MotoBLEMediaState(
    val sourceName: String = "Smartphone",
    val trackTitle: String = "In attesa di connessione",
    val artistName: String = "Media",
    val flags: Long = 0L
)
