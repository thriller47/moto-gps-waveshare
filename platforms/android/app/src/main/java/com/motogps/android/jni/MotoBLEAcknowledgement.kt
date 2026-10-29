package com.motogps.android.jni

/** ACK payload in wire order, independent from the ACK frame sequence. */
data class MotoBLEAcknowledgement(
    val acknowledgedSequence: Int,
    val status: Int,
    val commandId: Int
)
