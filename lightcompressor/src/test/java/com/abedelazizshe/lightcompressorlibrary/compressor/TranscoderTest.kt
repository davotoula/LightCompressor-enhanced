package com.abedelazizshe.lightcompressorlibrary.compressor

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import com.abedelazizshe.lightcompressorlibrary.CompressionProgressListener
import com.abedelazizshe.lightcompressorlibrary.VideoCodec
import com.abedelazizshe.lightcompressorlibrary.utils.StreamableVideo
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class TranscoderTest {

    private val context: Context = mockk(relaxed = true)
    private val uri: Uri = mockk()
    private val listener: CompressionProgressListener = mockk(relaxed = true)

    @Before
    fun setUp() {
        mockkObject(StreamableVideo)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun tuneEncoderFormat_h264_setsAvcProfileAndLevel() {
        val transcoder = object : Transcoder(VideoCodec.H264, context, uri, baseRequest()) {
            override fun codecSupportsProfile(mime: String, profile: Int, level: Int) = true
        }
        val format = mockk<MediaFormat>(relaxed = true)
        transcoder.tuneEncoderFormat(format)
        verify {
            format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
        }
    }

    @Test
    fun tuneEncoderFormat_h265_appliesProfileAndVendorHints() {
        val appliedKeys = mutableListOf<Pair<String, Int>>()
        val transcoder = object : Transcoder(VideoCodec.H265, context, uri, baseRequest()) {
            override fun codecSupportsProfile(mime: String, profile: Int, level: Int) = true
            override fun trySetVendorKey(format: MediaFormat, key: String, value: Int) {
                appliedKeys += key to value
                format.setInteger(key, value)
            }
        }
        val format = mockk<MediaFormat>(relaxed = true)
        transcoder.tuneEncoderFormat(format)
        verify {
            format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            verify {
                format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel4)
            }
        }
        assertTrue("Expected vendor hints", appliedKeys.isNotEmpty())
    }

    @Test
    fun finalizeOutput_withoutStreamable_renamesMuxerToDestination() {
        val muxerOutput = File.createTempFile("transcoder_mux", ".mp4").apply {
            deleteOnExit(); writeText("mux")
        }
        val destination = File.createTempFile("transcoder_dest", ".mp4").apply {
            deleteOnExit(); writeText("dest-initial")
        }
        val transcoder = Transcoder(VideoCodec.H264, context, uri, baseRequest(destination = destination))
        val result = transcoder.finalizeOutput(muxerOutput, streamableRequested = false)
        assertTrue(destination.exists())
        assertEquals("mux", destination.readText())
        assertEquals(destination.path, result.path)
        verify(exactly = 0) { StreamableVideo.start(any(), any()) }
    }

    @Test
    fun finalizeOutput_withoutStreamable_sameFileSkipsRename() {
        val destination = File.createTempFile("transcoder_dest", ".mp4").apply {
            deleteOnExit(); writeText("muxer-output")
        }
        val transcoder = Transcoder(VideoCodec.H264, context, uri, baseRequest(destination = destination))
        val result = transcoder.finalizeOutput(destination, streamableRequested = false)
        assertTrue(destination.exists())
        assertEquals("muxer-output", destination.readText())
        assertEquals(destination.path, result.path)
        verify(exactly = 0) { StreamableVideo.start(any(), any()) }
    }

    @Test
    fun finalizeOutput_withStreamable_prefersSecondaryOutput() {
        val muxerOutput = File.createTempFile("transcoder_mux", ".mp4").apply {
            deleteOnExit(); writeText("mux")
        }
        val destination = File.createTempFile("transcoder_dest", ".mp4").apply {
            deleteOnExit(); delete()
        }
        val streamable = File.createTempFile("transcoder_stream", ".mp4").apply {
            deleteOnExit(); delete()
        }
        every { StreamableVideo.start(muxerOutput, destination) } answers {
            destination.writeText("fast-start"); true
        }
        every { StreamableVideo.start(destination, streamable) } answers {
            streamable.writeText("streamable"); true
        }
        val transcoder = Transcoder(
            VideoCodec.H264, context, uri,
            baseRequest(destination = destination, streamablePath = streamable.path)
        )
        val result = transcoder.finalizeOutput(muxerOutput, streamableRequested = true)
        assertTrue(streamable.exists())
        assertEquals("streamable", streamable.readText())
        assertFalse("Destination should be removed", destination.exists())
        assertEquals(streamable.path, result.path)
        verifySequence {
            StreamableVideo.start(muxerOutput, destination)
            StreamableVideo.start(destination, streamable)
        }
    }

    private fun baseRequest(
        destination: File = File.createTempFile("transcoder_dest", ".mp4").apply {
            deleteOnExit(); delete()
        },
        streamablePath: String? = null
    ) = Transcoder.Request(
        index = 0, width = 1280, height = 720, bitrate = 2_000_000L,
        destination = destination, streamablePath = streamablePath,
        disableAudio = true, rotation = 0, durationUs = 1_000_000L,
        listener = listener
    )
}
