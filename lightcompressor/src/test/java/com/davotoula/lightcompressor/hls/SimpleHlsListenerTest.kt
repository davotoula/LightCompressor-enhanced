package com.davotoula.lightcompressor.hls

import com.davotoula.lightcompressor.Resolution
import org.junit.Test
import java.io.File

class SimpleHlsListenerTest {
    @Test
    fun `can be instantiated with zero overrides`() {
        val listener = object : SimpleHlsListener() {}
        // Exercising each default implementation to prove they don't throw.
        listener.onStart(renditionCount = 1)
        val rendition = Rendition(Resolution.HD_720, bitrateKbps = 2500)
        listener.onRenditionStart(rendition)
        listener.onSegmentReady(
            rendition,
            HlsSegment(
                file = File("tmp"),
                index = 0,
                durationSeconds = 6.0,
                isInitSegment = true,
            ),
        )
        listener.onRenditionComplete(
            rendition,
            HlsRenditionSummary(
                rendition = rendition,
                mediaPlaylist = "",
                playlistFilename = "720p/media.m3u8",
                width = 1280,
                height = 720,
                codecString = "avc1.64001F",
                combinedFilename = null,
            ),
        )
        listener.onComplete(masterPlaylist = "")
        listener.onFailure(HlsError("oops", emptyList(), emptyList()))
        listener.onProgress(rendition, percent = 50f)
        listener.onCancelled()
    }

    @Test
    fun `subclasses can override a subset of callbacks`() {
        var completeCount = 0
        val listener =
            object : SimpleHlsListener() {
                override fun onComplete(masterPlaylist: String) {
                    completeCount += 1
                }
            }
        listener.onComplete("")
        listener.onComplete("")
        assert(completeCount == 2)
    }
}
