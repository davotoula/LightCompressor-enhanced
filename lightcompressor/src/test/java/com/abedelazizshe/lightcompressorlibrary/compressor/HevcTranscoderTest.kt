package com.abedelazizshe.lightcompressorlibrary.compressor

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import com.abedelazizshe.lightcompressorlibrary.CompressionProgressListener
import com.abedelazizshe.lightcompressorlibrary.utils.StreamableVideo
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class HevcTranscoderTest {

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
    fun tuneHevcEncoderFormat_appliesProfileAndVendorHints() {
        val appliedKeys = mutableListOf<Pair<String, Int>>()
        val transcoder = object : HevcTranscoder(context, uri, baseRequest()) {
            override fun codecSupportsProfile(mime: String, profile: Int, level: Int): Boolean = true

            override fun trySetVendorKey(format: MediaFormat, key: String, value: Int) {
                appliedKeys += key to value
                format.setInteger(key, value)
            }
        }

        val format = mockk<MediaFormat>(relaxed = true)
        transcoder.tuneHevcEncoderFormat(format)

        verify {
            format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            verify {
                format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel4)
            }
        }

        assertTrue(
            "Expected at least one vendor hint to be applied",
            appliedKeys.isNotEmpty()
        )
    }

    @Test
    fun finalizeOutput_copiesTempFileWhenFastStartFails() {
        val destination = File.createTempFile("hevc_transcoder_dest", ".mp4").apply {
            deleteOnExit()
            writeText("muxer-output")
        }

        val transcoder = HevcTranscoder(
            context,
            uri,
            baseRequest(destination = destination)
        )

        val result = transcoder.finalizeOutput(destination, streamableRequested = false)

        assertTrue(destination.exists())
        assertEquals("muxer-output", destination.readText())
        assertEquals(destination.path, result.path)
        verify(exactly = 0) { StreamableVideo.start(any(), any()) }
    }

    @Test
    fun finalizeOutput_prefersStreamableTargetWhenAvailable() {
        val temp = File.createTempFile("hevc_transcoder", ".mp4").apply {
            deleteOnExit()
            writeText("temp-data")
        }
        val destination = File.createTempFile("hevc_transcoder_dest", ".mp4").apply {
            deleteOnExit()
            delete()
        }
        val streamable = File.createTempFile("hevc_transcoder_stream", ".mp4").apply {
            deleteOnExit()
            delete()
        }

        every { StreamableVideo.start(temp, destination) } answers {
            destination.writeText("fast-start")
            true
        }
        every { StreamableVideo.start(destination, streamable) } answers {
            streamable.writeText("streamable")
            true
        }

        val transcoder = HevcTranscoder(
            context,
            uri,
            baseRequest(destination = destination, streamablePath = streamable.path)
        )

        val result = transcoder.finalizeOutput(temp, streamableRequested = true)

        assertTrue(streamable.exists())
        assertEquals("streamable", streamable.readText())
        assertFalse("Destination should be removed when streamable copy succeeds", destination.exists())
        assertEquals(streamable.path, result.path)

        verifySequence {
            StreamableVideo.start(temp, destination)
            StreamableVideo.start(destination, streamable)
        }
    }

    private fun baseRequest(
        destination: File = File.createTempFile("hevc_transcoder_dest", ".mp4").apply {
            deleteOnExit()
            delete()
        },
        streamablePath: String? = null
    ) = HevcTranscoder.Request(
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
