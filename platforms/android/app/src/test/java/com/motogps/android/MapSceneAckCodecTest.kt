package com.motogps.android

import com.motogps.android.jni.*
import org.junit.Assert.*
import org.junit.Test

class MapSceneAckCodecTest {
    @Test fun mapSceneRequestsAcknowledgementAndInboundAckIsDecoded() {
        val codec = MotoBLEProtocolCodec()
        val ptr = codec.nativeInit(80)
        val scene = MotoBLEMapScene(
            sceneRevision = 5L,
            viewOrigin = GeoPointE6(39000000, 9000000),
            roads = emptyList()
        )
        val frames = codec.nativeEncodeMapScene(ptr, scene)
        assertTrue(frames.isNotEmpty())
        assertTrue((frames[0][3].toInt() and MotoBLEProtocolCodec.ACK_REQUESTED) != 0)
        val sequence = (frames[0][4].toInt() and 255) or ((frames[0][5].toInt() and 255) shl 8)
        val ackPayload = byteArrayOf(1, (sequence and 255).toByte(), (sequence shr 8).toByte(), 0, 0, 0)
        val raw = ByteArray(10 + ackPayload.size + 2)
        raw[0] = MotoBLEProtocolCodec.FRAME_MAGIC
        raw[1] = MotoBLEProtocolCodec.PROTOCOL_VERSION
        raw[2] = MotoBLEProtocolCodec.MSG_ACK
        raw[3] = 3
        raw[8] = ackPayload.size.toByte()
        ackPayload.copyInto(raw, 10)
        val crc = MotoBLEProtocolCodec.crc16CcittFalse(raw, raw.size - 2)
        raw[raw.size - 2] = crc.toByte()
        raw[raw.size - 1] = (crc shr 8).toByte()
        val message = codec.nativePushDeviceFrame(ptr, raw, 123L)
        assertTrue(message.complete)
        assertEquals(sequence, message.ack?.acknowledgedSequence)
        assertEquals(0, message.ack?.status)
    }
}
