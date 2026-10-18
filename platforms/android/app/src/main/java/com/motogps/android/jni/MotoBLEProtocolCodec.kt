package com.motogps.android.jni

class MotoBLEProtocolCodec(maximumFrameSize: Int = 182) {
    private var nativePtr: Long = 0L

    init {
        System.loadLibrary("motogps_jni")
        nativePtr = nativeInit(maximumFrameSize)
    }

    fun destroy() {
        if (nativePtr != 0L) {
            nativeDestroy(nativePtr)
            nativePtr = 0L
        }
    }

    fun setMaximumFrameSize(maximumFrameSize: Int) {
        if (nativePtr != 0L) {
            nativeSetMaximumFrameSize(nativePtr, maximumFrameSize)
        }
    }

    fun resetInboundState() {
        if (nativePtr != 0L) {
            nativeResetInboundState(nativePtr)
        }
    }

    fun encodePhoneStarting(sessionId: Long): Array<ByteArray> {
        return if (nativePtr != 0L) nativeEncodePhoneStarting(nativePtr, sessionId) else emptyArray()
    }

    fun encodePhoneReady(sessionId: Long): Array<ByteArray> {
        return if (nativePtr != 0L) nativeEncodePhoneReady(nativePtr, sessionId) else emptyArray()
    }

    fun encodeHeartbeat(sessionId: Long, monotonicMs: Long): Array<ByteArray> {
        return if (nativePtr != 0L) nativeEncodeHeartbeat(nativePtr, sessionId, monotonicMs) else emptyArray()
    }

    fun encodeAck(sequence: Int, status: Int, commandId: Int): Array<ByteArray> {
        return if (nativePtr != 0L) nativeEncodeAck(nativePtr, sequence, status, commandId) else emptyArray()
    }

    fun encodeNavigationSnapshot(snapshot: MotoBLENavigationSnapshot): Array<ByteArray> {
        return if (nativePtr != 0L) nativeEncodeNavigationSnapshot(nativePtr, snapshot) else emptyArray()
    }

    fun encodeNavigationPayloadForGoldenCheck(snapshot: MotoBLENavigationSnapshot): ByteArray? {
        return if (nativePtr != 0L) nativeEncodeNavigationPayloadForGoldenCheck(nativePtr, snapshot) else null
    }

    fun encodeMediaState(state: MotoBLEMediaState): Array<ByteArray> {
        return if (nativePtr != 0L) nativeEncodeMediaState(nativePtr, state) else emptyArray()
    }

    fun pushDeviceFrame(frame: ByteArray, receivedAtMs: Long): MotoBLEInboundMessage? {
        return if (nativePtr != 0L) nativePushDeviceFrame(nativePtr, frame, receivedAtMs) else null
    }

    fun getLastEncodedSequence(): Int {
        return if (nativePtr != 0L) nativeGetLastEncodedSequence(nativePtr) else 0
    }

    private external fun nativeInit(maximumFrameSize: Int): Long
    private external fun nativeDestroy(ptr: Long)
    private external fun nativeSetMaximumFrameSize(ptr: Long, maximumFrameSize: Int)
    private external fun nativeResetInboundState(ptr: Long)
    private external fun nativeEncodePhoneStarting(ptr: Long, sessionId: Long): Array<ByteArray>
    private external fun nativeEncodePhoneReady(ptr: Long, sessionId: Long): Array<ByteArray>
    private external fun nativeEncodeHeartbeat(ptr: Long, sessionId: Long, monotonicMs: Long): Array<ByteArray>
    private external fun nativeEncodeAck(ptr: Long, sequence: Int, status: Int, commandId: Int): Array<ByteArray>
    private external fun nativeEncodeNavigationSnapshot(ptr: Long, snapshot: MotoBLENavigationSnapshot): Array<ByteArray>
    private external fun nativeEncodeNavigationPayloadForGoldenCheck(ptr: Long, snapshot: MotoBLENavigationSnapshot): ByteArray?
    private external fun nativeEncodeMediaState(ptr: Long, state: MotoBLEMediaState): Array<ByteArray>
    private external fun nativePushDeviceFrame(ptr: Long, frame: ByteArray, receivedAtMs: Long): MotoBLEInboundMessage?
    private external fun nativeGetLastEncodedSequence(ptr: Long): Int
}
