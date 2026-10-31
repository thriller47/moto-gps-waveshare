package com.motogps.android.jni

/** Numeric, coordinate-free ESP32 diagnostic event. */
data class MotoBLEDiagnosticRecord(
    val sequence: Long,
    val uptimeMs: Long,
    val event: Int,
    val values: List<Int>,
    val reserved: Int = 0
)

data class MotoBLEInboundMessage(
    val sequence: Int,
    val ackReq: Boolean,
    val complete: Boolean,
    val duplicate: Boolean,
    val connStatus: MotoBLEConnectionStatus?,
    val heartbeat: MotoBLEHeartbeat?,
    val ack: MotoBLEAcknowledgement?,
    val command: MotoBLEDeviceCommand?,
    val diagnosticRecords: List<MotoBLEDiagnosticRecord> = emptyList()
)
