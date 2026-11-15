package com.motogps.android.jni

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies BLE reassembly without modifying the ESP32 wire format. */
class MotoBLEProtocolCodecFragmentTest {
    private val codec = MotoBLEProtocolCodec()

    private fun frame(
        payload: ByteArray,
        flags: Int,
        offset: Int,
        length: Int,
        sequence: Int = 21,
        type: Byte = MotoBLEProtocolCodec.MSG_DEVICE_COMMAND
    ): ByteArray {
        val out = ByteArray(MotoBLEProtocolCodec.FRAME_OVERHEAD + payload.size)
        out[0] = MotoBLEProtocolCodec.FRAME_MAGIC
        out[1] = MotoBLEProtocolCodec.PROTOCOL_VERSION
        out[2] = type
        out[3] = flags.toByte()
        out[4] = sequence.toByte()
        out[5] = (sequence ushr 8).toByte()
        out[6] = offset.toByte()
        out[7] = (offset ushr 8).toByte()
        out[8] = length.toByte()
        out[9] = (length ushr 8).toByte()
        payload.copyInto(out, destinationOffset = 10)
        val crc = MotoBLEProtocolCodec.crc16CcittFalse(out, out.size - 2)
        out[out.size - 2] = crc.toByte()
        out[out.size - 1] = (crc ushr 8).toByte()
        return out
    }

    @Test fun acceptsContiguousFragments() {
        val ptr = codec.nativeInit(40)
        try {
            val payload = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13)
            assertFalse(codec.nativePushDeviceFrame(
                ptr, frame(payload.copyOfRange(0, 6),
                    MotoBLEProtocolCodec.FRAME_START, 0, payload.size), 100L).complete)
            assertTrue(codec.nativePushDeviceFrame(
                ptr, frame(payload.copyOfRange(6, payload.size),
                    MotoBLEProtocolCodec.FRAME_END, 6, payload.size), 200L).complete)
        } finally { codec.nativeDestroy(ptr) }
    }

    @Test fun rejectsOutOfOrderOffsetAndRecovers() {
        val ptr = codec.nativeInit(40)
        try {
            val payload = byteArrayOf(1, 2, 3, 4)
            codec.nativePushDeviceFrame(ptr, frame(payload.copyOfRange(0, 2),
                MotoBLEProtocolCodec.FRAME_START, 0, 4), 100L)
            assertFalse(codec.nativePushDeviceFrame(ptr, frame(payload.copyOfRange(2, 4),
                MotoBLEProtocolCodec.FRAME_END, 1, 4), 200L).complete)
            assertTrue(codec.nativePushDeviceFrame(ptr, frame(payload,
                MotoBLEProtocolCodec.FRAME_START or MotoBLEProtocolCodec.FRAME_END,
                0, 4), 300L).complete)
        } finally { codec.nativeDestroy(ptr) }
    }

    @Test fun rejectsStaleAndWrongSequence() {
        val ptr = codec.nativeInit(40)
        try {
            val start = frame(byteArrayOf(1, 2), MotoBLEProtocolCodec.FRAME_START, 0, 4)
            val end = frame(byteArrayOf(3, 4), MotoBLEProtocolCodec.FRAME_END, 2, 4)
            codec.nativePushDeviceFrame(ptr, start, 100L)
            assertFalse(codec.nativePushDeviceFrame(ptr, end, 2001L).complete)
            codec.nativePushDeviceFrame(ptr, start, 2100L)
            assertFalse(codec.nativePushDeviceFrame(ptr, frame(byteArrayOf(3, 4),
                MotoBLEProtocolCodec.FRAME_END, 2, 4, sequence = 22), 2200L).complete)
        } finally { codec.nativeDestroy(ptr) }
    }

    @Test fun rejectsBadCrcAndTruncatedEnd() {
        val ptr = codec.nativeInit(40)
        try {
            val broken = frame(byteArrayOf(1, 2),
                MotoBLEProtocolCodec.FRAME_START, 0, 4)
            broken[broken.lastIndex] = (broken.last().toInt() xor 0xFF).toByte()
            assertFalse(codec.nativePushDeviceFrame(ptr, broken, 100L).complete)
            assertFalse(codec.nativePushDeviceFrame(ptr, frame(byteArrayOf(1, 2),
                MotoBLEProtocolCodec.FRAME_START or MotoBLEProtocolCodec.FRAME_END,
                0, 4), 200L).complete)
        } finally { codec.nativeDestroy(ptr) }
    }
}
