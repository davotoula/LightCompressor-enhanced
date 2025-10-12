package com.abedelazizshe.lightcompressorlibrary.compressor

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.Uri
import com.abedelazizshe.lightcompressorlibrary.CompressionProgressListener
import com.abedelazizshe.lightcompressorlibrary.utils.StreamableVideo
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class AVCTranscoderTest {

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
    fun tuneAvcEncoderFormat_setsProfileAndLevelWhenSupported() {
        val transcoder = object : AVCTranscoder(context, uri, baseRequest()) {
            override fun codecSupportsProfile(mime: String, profile: Int, level: Int): Boolean = true
        }

        val format = mockk<MediaFormat>(relaxed = true)
        transcoder.tuneAvcEncoderFormat(format)

        verify {
            format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
            format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel4)
        }
    }

    @Test
    fun finalizeOutput_withoutStreamableRequestSkipsFastStart() {
        val muxerOutput = File.createTempFile("avc_transcoder_mux", ".mp4").apply {
            deleteOnExit()
            writeText("mux")
        }
        val destination = File.createTempFile("avc_transcoder_dest", ".mp4").apply {
            deleteOnExit()
            writeText("dest-initial")
        }

        val transcoder = AVCTranscoder(
            context,
            uri,
            baseRequest(destination = destination)
        )

        val result = transcoder.finalizeOutput(muxerOutput, streamableRequested = false)

        assertTrue(destination.exists())
        assertEquals("mux", destination.readText())
        assertEquals(destination.path, result.path)
        verify(exactly = 0) { StreamableVideo.start(any(), any()) }
    }

    @Test
    fun finalizeOutput_withStreamableRequestPrefersSecondaryOutput() {
        val muxerOutput = File.createTempFile("avc_transcoder_mux", ".mp4").apply {
            deleteOnExit()
            writeText("mux")
        }
        val destination = File.createTempFile("avc_transcoder_dest", ".mp4").apply {
            deleteOnExit()
            delete()
        }
        val streamable = File.createTempFile("avc_transcoder_stream", ".mp4").apply {
            deleteOnExit()
            delete()
        }

        every { StreamableVideo.start(muxerOutput, destination) } answers {
            destination.writeText("fast-start")
            true
        }
        every { StreamableVideo.start(destination, streamable) } answers {
            streamable.writeText("streamable")
            true
        }

        val transcoder = AVCTranscoder(
            context,
            uri,
            baseRequest(destination = destination, streamablePath = streamable.path)
        )

        val result = transcoder.finalizeOutput(muxerOutput, streamableRequested = true)

        assertTrue(streamable.exists())
        assertEquals("streamable", streamable.readText())
        assertFalse("Destination should be removed after streamable copy", destination.exists())
        assertEquals(streamable.path, result.path)

        verifySequence {
            StreamableVideo.start(muxerOutput, destination)
            StreamableVideo.start(destination, streamable)
        }
    }

    private fun baseRequest(
        destination: File = File.createTempFile("avc_transcoder_dest", ".mp4").apply {
            deleteOnExit()
            delete()
        },
        streamablePath: String? = null
    ) = AVCTranscoder.Request(
        index = 0,
        width = 1280,
        height = 720,
        bitrate = 2_000_000L,
        destination = destination,
        streamablePath = streamablePath,
        disableAudio = true,
        rotation = 0,
        durationUs = 1_000_000L,
        listener = listener
    )
}
