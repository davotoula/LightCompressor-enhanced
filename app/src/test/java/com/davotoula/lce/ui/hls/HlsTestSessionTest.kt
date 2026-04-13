package com.davotoula.lce.ui.hls

import com.davotoula.lightcompressor.Resolution
import com.davotoula.lightcompressor.hls.HlsError
import com.davotoula.lightcompressor.hls.HlsSegment
import com.davotoula.lightcompressor.hls.Rendition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HlsTestSessionTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var rootDir: File
    private var state: HlsTestState? = null
    private var ioFailureCount: Int = 0
    private lateinit var session: HlsTestSession

    private val rendition360 = Rendition(Resolution.SD_360, 500)
    private val rendition720 = Rendition(Resolution.HD_720, 2500)

    private fun seedPending(vararg renditions: Rendition): HlsTestState =
        HlsTestState(
            isRunning = false,
            renditions =
                renditions.map { r ->
                    HlsRenditionState(
                        label = r.resolution.label,
                        status = HlsRenditionStatus.Pending,
                    )
                },
        )

    @Before
    fun setUp() {
        rootDir = tempFolder.newFolder("hls-current")
        state = seedPending(rendition360, rendition720)
        ioFailureCount = 0
        session =
            HlsTestSession(
                rootDir = rootDir,
                updateState = { transform -> state = transform(state) },
                onIoFailure = { ioFailureCount++ },
            )
    }

    private fun makeSegment(
        index: Int,
        isInit: Boolean,
        contents: ByteArray = byteArrayOf(0x01, 0x02, 0x03),
    ): HlsSegment {
        val temp = tempFolder.newFile("seg-$index-$isInit.bin")
        temp.writeBytes(contents)
        return HlsSegment(
            file = temp,
            index = index,
            durationSeconds = 6.0,
            isInitSegment = isInit,
        )
    }

    @Test
    fun `onStart trims rendition rows to renditionCount and marks running`() {
        session.onStart(renditionCount = 1)

        val current = state!!
        assertTrue(current.isRunning)
        assertEquals(1, current.renditions.size)
        assertEquals("360p", current.renditions[0].label)
    }

    @Test
    fun `onRenditionStart marks the matching row Active and resets progress`() {
        session.onStart(renditionCount = 2)
        session.onRenditionStart(rendition720)

        val row = state!!.renditions.first { it.label == "720p" }
        assertEquals(HlsRenditionStatus.Active, row.status)
        assertEquals(0, row.progressPercent)
    }

    @Test
    fun `onSegmentReady with init segment writes init mp4 and does not bump segmentCount`() {
        session.onStart(renditionCount = 2)
        session.onRenditionStart(rendition360)
        session.onSegmentReady(rendition360, makeSegment(index = 0, isInit = true))

        val initFile = File(rootDir, "360p/init.mp4")
        assertTrue("init.mp4 should exist", initFile.exists())
        assertEquals(3L, initFile.length())

        val row = state!!.renditions.first { it.label == "360p" }
        assertEquals(0, row.segmentCount)
    }

    @Test
    fun `onSegmentReady with media segment writes segment file and bumps segmentCount`() {
        session.onStart(renditionCount = 2)
        session.onRenditionStart(rendition360)
        session.onSegmentReady(rendition360, makeSegment(index = 0, isInit = false))
        session.onSegmentReady(rendition360, makeSegment(index = 1, isInit = false))

        assertTrue(File(rootDir, "360p/segment_000.m4s").exists())
        assertTrue(File(rootDir, "360p/segment_001.m4s").exists())

        val row = state!!.renditions.first { it.label == "360p" }
        assertEquals(2, row.segmentCount)
    }

    @Test
    fun `onProgress updates the matching rendition's progressPercent`() {
        session.onStart(renditionCount = 2)
        session.onRenditionStart(rendition360)
        session.onProgress(rendition360, percent = 47.0f)

        val row = state!!.renditions.first { it.label == "360p" }
        assertEquals(47, row.progressPercent)
    }

    @Test
    fun `onRenditionComplete writes media m3u8 and marks row Complete`() {
        session.onStart(renditionCount = 2)
        session.onRenditionStart(rendition360)
        session.onRenditionComplete(rendition360, playlist = "#EXTM3U\n# fake\n")

        val mediaFile = File(rootDir, "360p/media.m3u8")
        assertTrue(mediaFile.exists())
        assertEquals("#EXTM3U\n# fake\n", mediaFile.readText())

        val row = state!!.renditions.first { it.label == "360p" }
        assertEquals(HlsRenditionStatus.Complete, row.status)
        assertEquals(100, row.progressPercent)
    }

    @Test
    fun `onComplete writes master m3u8 and emits Succeeded terminal with absolute path`() {
        session.onStart(renditionCount = 2)
        session.onComplete(masterPlaylist = "#EXTM3U\n# master\n")

        val masterFile = File(rootDir, "master.m3u8")
        assertTrue(masterFile.exists())
        assertEquals("#EXTM3U\n# master\n", masterFile.readText())

        val current = state!!
        assertFalse(current.isRunning)
        val terminal = current.terminal as HlsTerminal.Succeeded
        assertEquals(masterFile.absolutePath, terminal.masterPlaylistPath)
    }

    @Test
    fun `onFailure marks any Active row Failed and emits Failed terminal`() {
        session.onStart(renditionCount = 2)
        session.onRenditionStart(rendition720)
        session.onFailure(
            HlsError(
                message = "encoder died",
                failedRenditions = listOf(rendition720),
                completedRenditions = emptyList(),
            ),
        )

        val current = state!!
        assertFalse(current.isRunning)
        val terminal = current.terminal as HlsTerminal.Failed
        assertEquals("encoder died", terminal.message)

        val row = current.renditions.first { it.label == "720p" }
        assertEquals(HlsRenditionStatus.Failed, row.status)
    }

    @Test
    fun `onCancelled emits Cancelled terminal`() {
        session.onStart(renditionCount = 2)
        session.onCancelled()

        val current = state!!
        assertFalse(current.isRunning)
        assertEquals(HlsTerminal.Cancelled, current.terminal)
    }

    @Test
    fun `onCancelled preserves an existing Failed terminal from IO failure`() {
        // Simulate the IO-failure → cancel → onCancelled race: a segment write fails,
        // failWithIoError sets Failed and triggers HlsPreparer.cancel(), which eventually
        // calls onCancelled. The Failed terminal must survive.
        File(rootDir, "360p").writeText("blocker")

        session.onStart(renditionCount = 2)
        session.onRenditionStart(rendition360)
        session.onSegmentReady(rendition360, makeSegment(index = 0, isInit = false))

        val failedBefore = state!!.terminal as HlsTerminal.Failed
        session.onCancelled()

        val current = state!!
        assertFalse(current.isRunning)
        val failedAfter = current.terminal as HlsTerminal.Failed
        assertEquals(failedBefore.message, failedAfter.message)
    }

    @Test
    fun `onSegmentReady with unwritable target invokes onIoFailure and sets Failed terminal`() {
        // Make the rendition output dir read-only by creating a file where the dir should go.
        File(rootDir, "360p").writeText("blocker")

        session.onStart(renditionCount = 2)
        session.onRenditionStart(rendition360)
        session.onSegmentReady(rendition360, makeSegment(index = 0, isInit = false))

        assertEquals(1, ioFailureCount)
        val current = state!!
        assertFalse(current.isRunning)
        assertTrue(current.terminal is HlsTerminal.Failed)
    }
}
