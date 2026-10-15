package com.motogps.android.jni

class MotoBLEProtocolCodec(maximumFrameSize: Int) {

    private var nativePtr: Long = 0

    init {
        if (isLibraryLoaded) {
            nativePtr = nativeInit(maximumFrameSize)
        }
    }

    fun setMaximumFrameSize(maximumFrameSize: Int) {
        if (isLibraryLoaded) {
            nativeSetMaximumFrameSize(nativePtr, maximumFrameSize)
        }
    }

    fun resetInboundState() {
        if (isLibraryLoaded) {
            nativeResetInboundState(nativePtr)
        }
    }

    fun encodePhoneStarting(sessionID: Long): List<ByteArray> {
        if (!isLibraryLoaded) return emptyList()
        return nativeEncodePhoneStarting(nativePtr, sessionID).toList()
    }

    fun encodePhoneReady(sessionID: Long): List<ByteArray> {
        if (!isLibraryLoaded) return emptyList()
        return nativeEncodePhoneReady(nativePtr, sessionID).toList()
    }

    fun encodeHeartbeat(sessionID: Long, monotonicMs: Long): List<ByteArray> {
        if (!isLibraryLoaded) return emptyList()
        return nativeEncodeHeartbeat(nativePtr, sessionID, monotonicMs).toList()
    }

    fun encodeAck(sequence: Int, status: Int, commandID: Int): List<ByteArray> {
        if (!isLibraryLoaded) return emptyList()
        return nativeEncodeAck(nativePtr, sequence, status, commandID).toList()
    }

    fun encodeNavigationSnapshot(snapshot: MotoBLENavigationSnapshotInput): List<ByteArray> {
        if (!isLibraryLoaded) return emptyList()
        return nativeEncodeNavigationSnapshot(nativePtr, snapshot).toList()
    }

    fun encodeNavigationPayloadForGoldenCheck(snapshot: MotoBLENavigationSnapshotInput): ByteArray {
        if (!isLibraryLoaded) return ByteArray(0)
        return nativeEncodeNavigationPayloadForGoldenCheck(nativePtr, snapshot)
    }

    fun encodeMediaState(state: MotoBLEMediaStateInput): List<ByteArray> {
        if (!isLibraryLoaded) return emptyList()
        return nativeEncodeMediaState(nativePtr, state).toList()
    }

    fun pushDeviceFrame(frame: ByteArray, receivedAtMs: Long): MotoBLEInboundMessage? {
        if (!isLibraryLoaded) return null
        return nativePushDeviceFrame(nativePtr, frame, receivedAtMs)
    }

    fun getLastEncodedSequence(): Int {
        if (!isLibraryLoaded) return 0
        return nativeGetLastEncodedSequence(nativePtr)
    }

    protected fun finalize() {
        if (nativePtr != 0L && isLibraryLoaded) {
            nativeDestroy(nativePtr)
            nativePtr = 0
        }
    }

    private external fun nativeInit(maximumFrameSize: Int): Long
    private external fun nativeDestroy(ptr: Long)
    private external fun nativeSetMaximumFrameSize(ptr: Long, maximumFrameSize: Int)
    private external fun nativeResetInboundState(ptr: Long)
    private external fun nativeEncodePhoneStarting(ptr: Long, sessionID: Long): Array<ByteArray>
    private external fun nativeEncodePhoneReady(ptr: Long, sessionID: Long): Array<ByteArray>
    private external fun nativeEncodeHeartbeat(ptr: Long, sessionID: Long, monotonicMs: Long): Array<ByteArray>
    private external fun nativeEncodeAck(ptr: Long, sequence: Int, status: Int, commandID: Int): Array<ByteArray>
    private external fun nativeEncodeNavigationSnapshot(ptr: Long, snapshot: MotoBLENavigationSnapshotInput): Array<ByteArray>
    private external fun nativeEncodeNavigationPayloadForGoldenCheck(ptr: Long, snapshot: MotoBLENavigationSnapshotInput): ByteArray
    private external fun nativeEncodeMediaState(ptr: Long, state: MotoBLEMediaStateInput): Array<ByteArray>
    private external fun nativePushDeviceFrame(ptr: Long, frame: ByteArray, receivedAtMs: Long): MotoBLEInboundMessage?
    private external fun nativeGetLastEncodedSequence(ptr: Long): Int

    companion object {
        var isLibraryLoaded = false
            private set

        var libraryLoadError: Throwable? = null
            private set

        init {
            try {
                System.loadLibrary("motogps_jni")
                isLibraryLoaded = true
            } catch (t: Throwable) {
                libraryLoadError = t
                isLibraryLoaded = false
            }
        }
    }
}
