package com.motogps.android

import com.motogps.android.jni.GeoPointE6
import com.motogps.android.jni.MotoBLEMapBuildingFootprint
import com.motogps.android.jni.MotoBLEMapRoadPolyline
import com.motogps.android.jni.MotoBLEMapScene
import com.motogps.android.jni.MotoBLEProtocolCodec
import com.motogps.android.map.HybridMapRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import android.content.Context

class HybridMapRepositoryTest {

    private lateinit var repository: HybridMapRepository

    @Before
    fun setUp() {
        val mockContext = mock(Context::class.java)
        repository = HybridMapRepository(mockContext)
    }

    @Test
    fun testParseHighwayClass() {
        assertEquals(0, repository.parseHighwayClass("motorway"))
        assertEquals(0, repository.parseHighwayClass("trunk"))
        assertEquals(1, repository.parseHighwayClass("primary"))
        assertEquals(2, repository.parseHighwayClass("secondary"))
        assertEquals(3, repository.parseHighwayClass("residential"))
        assertEquals(4, repository.parseHighwayClass("service"))
        assertEquals(5, repository.parseHighwayClass("unknown_road"))
    }

    @Test
    fun testParseMaxSpeedTag() {
        assertEquals(130, repository.parseMaxSpeedTag("130", 0))
        assertEquals(50, repository.parseMaxSpeedTag("50 km/h", 3))
        assertEquals(48, repository.parseMaxSpeedTag("30 mph", 2))
        assertEquals(50, repository.parseMaxSpeedTag("IT:urban", 3))
        assertEquals(90, repository.parseMaxSpeedTag("IT:rural", 2))
        assertEquals(130, repository.parseMaxSpeedTag("none", 0))
        assertEquals(50, repository.parseMaxSpeedTag(null, 3))
    }

    @Test
    fun testStreamOverpassToMotomap() {
        val jsonStr = """
        {
          "version": 0.6,
          "elements": [
            {
              "type": "way",
              "id": 12345,
              "tags": {
                "highway": "primary",
                "maxspeed": "90"
              },
              "geometry": [
                { "lat": 41.9028, "lon": 12.4964 },
                { "lat": 41.9030, "lon": 12.4966 }
              ]
            }
          ]
        }
        """.trimIndent()

        val inputStream = jsonStr.toByteArray(Charsets.UTF_8).inputStream()
        val stringWriter = java.io.StringWriter()
        val bufferedWriter = java.io.BufferedWriter(stringWriter)

        val writtenCount = repository.streamOverpassToMotomap(inputStream, bufferedWriter)
        bufferedWriter.flush()

        assertEquals(1, writtenCount)
        val outputStr = stringWriter.toString()
        assertTrue(outputStr.contains("1,90|41902800,12496400,41903000,12496600|41.902800,12.496400;41.903000,12.496600"))
    }

    @Test
    fun overpassHttp200WithRemarkIsNotACompleteMap() {
        val input = """
            {"elements":[{"type":"way","tags":{"highway":"residential"},
            "geometry":[{"lat":45.0,"lon":8.0},{"lat":45.001,"lon":8.001}]}],
            "remark":"runtime error: query timed out"}
        """.trimIndent().byteInputStream()
        val output = java.io.BufferedWriter(java.io.StringWriter())
        try {
            repository.streamOverpassToMotomap(input, output)
            org.junit.Assert.fail("A runtime remark must invalidate a sector")
        } catch (_: java.io.IOException) {
            // A 200 response with a runtime error is not a complete tile.
        }
    }

    @Test
    fun truncatedOverpassElementsAreNotCached() {
        val input = """{"elements":[{"type":"way","tags":{"highway":"primary"}}""".byteInputStream()
        val output = java.io.BufferedWriter(java.io.StringWriter())
        try {
            repository.streamOverpassToMotomap(input, output)
            org.junit.Assert.fail("Truncated Overpass JSON must be rejected")
        } catch (_: java.io.IOException) {
            // Retry from a mirror instead of publishing a partial response.
        }
    }

    @Test
    fun testMapSceneFrameEncoding() {
        val codec = MotoBLEProtocolCodec()
        val handle = codec.nativeInit(247)

        val road = MotoBLEMapRoadPolyline(
            roadClass = 1,
            points = listOf(
                GeoPointE6(41902800, 12496400),
                GeoPointE6(41903000, 12496600)
            )
        )

        val scene = MotoBLEMapScene(
            coordinateSystem = 2,
            sceneRevision = 100L,
            viewOrigin = GeoPointE6(41902800, 12496400),
            radiusM = 500,
            roads = listOf(road),
            buildings = emptyList()
        )

        val frames = codec.nativeEncodeMapScene(handle, scene)
        assertNotNull(frames)
        assertTrue(frames.isNotEmpty())

        val firstFrame = frames[0]
        assertEquals(0xB7.toByte(), firstFrame[0]) // MAGIC
        assertEquals(0x01.toByte(), firstFrame[1]) // PROTOCOL_VERSION
        assertEquals(0x14.toByte(), firstFrame[2]) // MSG_MAP_SCENE

        val payload = codec.nativeEncodeMapScenePayloadForGoldenCheck(handle, scene)
        assertNotNull(payload)
        assertTrue(payload!!.size > 10)
    }
}
