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
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.`when`
import java.io.File
import com.motogps.android.map.GatewayRoadTiles
import org.mockito.Mockito.mock
import android.content.Context

class HybridMapRepositoryTest {

    private lateinit var repository: HybridMapRepository

    @Before
    fun setUp() {
        val mockContext = mock(Context::class.java)
        repository = HybridMapRepository(mockContext)
    }

    @get:Rule
    val temporaryFiles = TemporaryFolder()

    @Test
    fun readsCompletedCorridorCheckpointBeforeFullMapIsAssembled() {
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(temporaryFiles.root)
        val cache = HybridMapRepository(context)
        val maps = File(temporaryFiles.root, "maps")
        val checkpoints = File(maps, ".corridoio_rotta_parts")
        assertTrue(checkpoints.mkdirs())
        val tile = GatewayRoadTiles.tileFor(45.0, 8.0)
        File(checkpoints, "tile_${tile.key}.motomap").writeText(
            "# completed tile\n3,50|45000000,8000000,45000100,8000000|45000000,8000000;45000100,8000000\n"
        )
        val roads = cache.loadFromRegionalFiles(44_995_000, 7_995_000, 45_005_000, 8_005_000)
        assertEquals(1, roads.size)
        assertEquals(2, roads.single().points.size)
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
