package com.motogps.android

import com.motogps.android.jni.GeoPointE6
import com.motogps.android.jni.MotoBLENavigationSnapshot
import com.motogps.android.jni.MotoBLEProtocolCodec
import com.motogps.android.jni.MotoBLERouteGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MotoBLEProtocolCodecTest {

    private lateinit var codec: MotoBLEProtocolCodec
    private var handle: Long = 0L

    @Before
    fun setUp() {
        codec = MotoBLEProtocolCodec()
        handle = codec.nativeInit(247)
    }

    @Test
    fun diagnosticControlUsesDedicatedProtocolType() {
        for (mode in 0..2) {
            val frames = codec.nativeEncodeDiagnosticControl(handle, mode)
            assertEquals(1, frames.size)
            assertEquals(MotoBLEProtocolCodec.MSG_DIAGNOSTIC_CONTROL, frames[0][2])
            assertEquals(1.toByte(), frames[0][10])
            assertEquals(mode.toByte(), frames[0][11])
        }
        assertTrue(codec.nativeEncodeDiagnosticControl(handle, 3).isEmpty())
    }

    @Test
    fun diagnosticRecordsDecodeWithoutGpsData() {
        val payload = ByteArray(34)
        payload[0] = 1 // revision
        payload[1] = 1 // one record
        payload[2] = 42 // sequence
        payload[6] = 100 // uptime milliseconds
        payload[10] = 7 // RenderLockTimeout
        payload[14] = 250.toByte() // first signed int32 event field
        val frame = ByteArray(12 + payload.size)
        frame[0] = 0xB7.toByte()
        frame[1] = 1
        frame[2] = MotoBLEProtocolCodec.MSG_DIAGNOSTIC_BATCH
        frame[3] = 3
        frame[4] = 5
        frame[8] = payload.size.toByte()
        System.arraycopy(payload, 0, frame, 10, payload.size)
        val crc = MotoBLEProtocolCodec.crc16CcittFalse(frame, frame.size - 2)
        frame[frame.size - 2] = crc.toByte()
        frame[frame.size - 1] = (crc ushr 8).toByte()
        val result = codec.nativePushDeviceFrame(handle, frame, 1000)
        assertTrue(result.complete)
        assertEquals(1, result.diagnosticRecords.size)
        assertEquals(42L, result.diagnosticRecords.single().sequence)
        assertEquals(7, result.diagnosticRecords.single().event)
        assertEquals(250, result.diagnosticRecords.single().values.first())
    }

    @Test
    fun testPhoneStartingFrameEncoding() {
        val sessionId = 12345678L
        val frames = codec.nativeEncodePhoneStarting(handle, sessionId)

        assertEquals(1, frames.size)
        val frame = frames[0]

        // Frame header checks
        assertEquals(0xB7.toByte(), frame[0]) // MAGIC
        assertEquals(0x01.toByte(), frame[1]) // PROTOCOL_VERSION
        assertEquals(0x01.toByte(), frame[2]) // MSG_CONNECTION_STATUS
        // Flags: START (1) | END (2) | URGENT (8) = 11 (0x0B)
        assertEquals(0x0B.toByte(), frame[3])

        // Sequence number = 1 in Little Endian uint16
        assertEquals(0x01.toByte(), frame[4])
        assertEquals(0x00.toByte(), frame[5])

        // CRC check
        val crcOffset = frame.size - 2
        val expectedCrc = (frame[crcOffset].toInt() and 0xFF) or ((frame[crcOffset + 1].toInt() and 0xFF) shl 8)
        val actualCrc = MotoBLEProtocolCodec.crc16CcittFalse(frame, crcOffset)
        assertEquals(expectedCrc, actualCrc)

        // Payload checks
        // byte 10: Payload Revision (1)
        assertEquals(0x01.toByte(), frame[10])
        // byte 11: Role Phone (1)
        assertEquals(0x01.toByte(), frame[11])
        // byte 12: State Starting (0)
        assertEquals(0x00.toByte(), frame[12])
    }

    @Test
    fun testPhoneReadyFrameEncoding() {
        val sessionId = 87654321L
        val frames = codec.nativeEncodePhoneReady(handle, sessionId)

        assertEquals(1, frames.size)
        val frame = frames[0]

        assertEquals(0xB7.toByte(), frame[0]) // MAGIC
        assertEquals(0x01.toByte(), frame[1]) // PROTOCOL_VERSION
        assertEquals(0x01.toByte(), frame[2]) // MSG_CONNECTION_STATUS
        assertEquals(0x0B.toByte(), frame[3]) // Flags: START | END | URGENT

        // byte 10: Payload Revision (1)
        assertEquals(0x01.toByte(), frame[10])
        // byte 11: Role Phone (1)
        assertEquals(0x01.toByte(), frame[11])
        // byte 12: State Ready (1)
        assertEquals(0x01.toByte(), frame[12])
    }

    @Test
    fun testHeartbeatFrameEncoding() {
        val sessionId = 1000L
        val monotonicMs = 5000L
        val frames = codec.nativeEncodeHeartbeat(handle, sessionId, monotonicMs)

        assertEquals(1, frames.size)
        val frame = frames[0]

        assertEquals(0xB7.toByte(), frame[0]) // MAGIC
        assertEquals(0x01.toByte(), frame[1]) // PROTOCOL_VERSION
        assertEquals(0x02.toByte(), frame[2]) // MSG_HEARTBEAT
        assertEquals(0x03.toByte(), frame[3]) // Flags: START | END (3)

        val crcOffset = frame.size - 2
        val expectedCrc = (frame[crcOffset].toInt() and 0xFF) or ((frame[crcOffset + 1].toInt() and 0xFF) shl 8)
        val actualCrc = MotoBLEProtocolCodec.crc16CcittFalse(frame, crcOffset)
        assertEquals(expectedCrc, actualCrc)
    }

    @Test
    fun testPushDeviceFrameReassembly() {
        // Synthesize an inbound DeviceReady connection status message
        // Payload: rev(1), role=2(Device), state=1(Ready), minVer=1, maxVer=1, capabilities=0, sessionId=100, maxFrameSize=247, heartbeatInterval=1000
        val payload = byteArrayOf(
            1, // payload rev
            2, // role Device
            1, // state Ready
            1, // minVer
            1, // maxVer
            0, 0, 0, 0, // capabilities uint32
            100, 0, 0, 0, // sessionId uint32
            247.toByte(), 0, // maxFrameSize uint16
            0xE8.toByte(), 0x03 // heartbeatInterval 1000 uint16
        )

        val header = byteArrayOf(
            0xB7.toByte(), // magic
            1, // version
            1, // MSG_CONNECTION_STATUS
            3, // flags: START | END
            1, 0, // seq=1
            0, 0, // offset=0
            payload.size.toByte(), 0 // len
        )

        val totalLen = header.size + payload.size + 2
        val frame = ByteArray(totalLen)
        System.arraycopy(header, 0, frame, 0, header.size)
        System.arraycopy(payload, 0, frame, header.size, payload.size)

        val crc = MotoBLEProtocolCodec.crc16CcittFalse(frame, totalLen - 2)
        frame[totalLen - 2] = (crc and 0xFF).toByte()
        frame[totalLen - 1] = ((crc shr 8) and 0xFF).toByte()

        val inbound = codec.nativePushDeviceFrame(handle, frame, System.currentTimeMillis())

        assertTrue(inbound.complete)
        assertNotNull(inbound.connStatus)
        assertEquals(2, inbound.connStatus!!.role) // Device
        assertEquals(1, inbound.connStatus!!.state) // Ready
        assertEquals(100L, inbound.connStatus!!.sessionId)
    }

    @Test
    fun testGoldenNavigationAndGeometryPayloads() {
        val expectedNavHex = "0103020007029300a61062bf040302012a0000007801000039300000fc030000340800006d380000e3013200a32326007d00000ce4b89ce995bfe5ae89e8a19712e5898de696b9e8b7afe58fa3e58fb3e8bdac"
        val expectedGeomHex = "0102a61062bf040302010000010000000400a42b6102a461f006049e2b61029861f006e001da0567a901d608800f"

        val navSnapshot = MotoBLENavigationSnapshot(
            stateName = "navigating",
            networkName = "online",
            displayPageName = "navigation",
            maneuverName = "turn_right",
            trafficName = "slow",
            hasDestination = true,
            hasFix = true,
            gnssStale = false,
            offRoute = false,
            routeRequestInFlight = false,
            trafficRequestInFlight = false,
            routeToken = 0xBF6210A6L,
            routeGeneration = 0x01020304L,
            maneuverID = 42L,
            distanceToManeuverM = 376L,
            remainingDistanceM = 12345L,
            remainingDurationS = 1020L,
            routeProgressM = 2100L,
            totalDistanceM = 14445L,
            speedDeciKPH = 483,
            speedLimitKPH = 50,
            headingCentiDegrees = 9123,
            accuracyDecimeters = 38,
            crossTrackDecimeters = 125,
            roundaboutExit = 0,
            instructionText = "东长安街\n前方路口右转"
        )

        val navPayload = codec.nativeEncodeNavigationPayloadForGoldenCheck(handle, navSnapshot)
        assertNotNull(navPayload)
        assertEquals(expectedNavHex, hexString(navPayload!!))
        // Payload offset 6 contains the navigation flags. A real turn plus
        // a valid route must set both HasNextManeuver (0x10) and
        // HasRouteView (0x80); these were previously omitted by JNI.
        assertEquals(0x93, navPayload[6].toInt() and 0xFF)
        val noTurn = navSnapshot.copy(
            maneuverName = "continue",
            distanceToManeuverM = 0L,
            instructionText = "Prosegui"
        )
        val noTurnPayload = codec.nativeEncodeNavigationPayloadForGoldenCheck(
            handle, noTurn)!!
        assertEquals(0x83, noTurnPayload[6].toInt() and 0xFF)
        // A road name or instruction must not make an otherwise invalid
        // "continue" maneuver actionable again.
        val namedContinuePayload = codec.nativeEncodeNavigationPayloadForGoldenCheck(
            handle, noTurn.copy(instructionText = "Via Roma\nProsegui"))!!
        assertEquals(0x83, namedContinuePayload[6].toInt() and 0xFF)

        // Before the route is active, or when the generation is missing,
        // the ESP32 must not interpret stale geometry/turn data as valid.
        val planningPayload = codec.nativeEncodeNavigationPayloadForGoldenCheck(
            handle, navSnapshot.copy(stateName = "planning"))!!
        assertEquals(0x03, planningPayload[6].toInt() and 0xFF)
        val incompleteRoutePayload = codec.nativeEncodeNavigationPayloadForGoldenCheck(
            handle, navSnapshot.copy(routeGeneration = 0L))!!
        assertEquals(0x03, incompleteRoutePayload[6].toInt() and 0xFF)
        val arrivedPayload = codec.nativeEncodeNavigationPayloadForGoldenCheck(
            handle, navSnapshot.copy(stateName = "arrived", maneuverName = "arrive"))!!
        assertEquals(0x93, arrivedPayload[6].toInt() and 0xFF)

        val idle = navSnapshot.copy(
            stateName = "idle", hasDestination = false,
            maneuverName = "none", routeToken = 0L,
            routeGeneration = 0L
        )
        val idlePayload = codec.nativeEncodeNavigationPayloadForGoldenCheck(handle, idle)!!
        assertEquals(0x02, idlePayload[6].toInt() and 0xFF)

        val routeGeometry = MotoBLERouteGeometry(
            coordinateSystem = 2,
            routeToken = 0xBF6210A6L,
            routeGeneration = 0x01020304L,
            chunkIndex = 0,
            chunkCount = 1,
            firstPointIndex = 0,
            totalPointCount = 4,
            viewOrigin = GeoPointE6(39922596, 116416932),
            points = listOf(
                GeoPointE6(39922590, 116416920),
                GeoPointE6(39922702, 116417285),
                GeoPointE6(39922650, 116417200),
                GeoPointE6(39923205, 116418160)
            )
        )

        val geomPayload = codec.nativeEncodeGeometryPayloadForGoldenCheck(handle, routeGeometry)
        assertNotNull(geomPayload)
        assertEquals(expectedGeomHex, hexString(geomPayload!!))
    }

    private fun hexString(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) {
            sb.append(String.format("%02x", b.toInt() and 0xFF))
        }
        return sb.toString()
    }
}
