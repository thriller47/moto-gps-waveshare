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
    val sessionID: Long,
    val maximumFrameSize: Int,
    val heartbeatIntervalMs: Int
)

data class MotoBLEHeartbeat(
    val sessionID: Long
)

data class MotoBLEAcknowledgement(
    val acknowledgedSequence: Int,
    val status: Int
)

data class MotoBLEDeviceCommand(
    val sequence: Int,
    val ackRequested: Boolean,
    val kind: Int,
    val commandID: Int,
    val page: Int,
    val x: Int,
    val y: Int,
    val eventTimeMs: Long
)

data class MotoBLENavigationSnapshotInput(
    val stateName: String,
    val networkName: String,
    val displayPageName: String,
    val maneuverName: String,
    val trafficName: String,
    val hasDestination: Boolean,
    val hasFix: Boolean,
    val gnssStale: Boolean,
    val offRoute: Boolean,
    val routeRequestInFlight: Boolean,
    val trafficRequestInFlight: Boolean,
    val hasRouteView: Boolean,
    val routeToken: Long,
    val routeGeneration: Long,
    val maneuverID: Long,
    val distanceToManeuverM: Long,
    val remainingDistanceM: Long,
    val remainingDurationS: Long,
    val routeProgressM: Long,
    val totalDistanceM: Long,
    val speedDeciKPH: Int,
    val speedLimitKPH: Int,
    val headingCentiDegrees: Int,
    val accuracyDecimeters: Int,
    val crossTrackDecimeters: Int,
    val roundaboutExit: Int,
    val roadName: String,
    val instructionText: String
)

data class MotoBLEMediaStateInput(
    val connected: Boolean,
    val playing: Boolean,
    val likeAvailable: Boolean,
    val liked: Boolean,
    val trackToken: Long,
    val positionSeconds: Int,
    val durationSeconds: Int,
    val sourceName: String,
    val trackTitle: String,
    val artistName: String
)
