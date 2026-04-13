package com.davotoula.lightcompressor.utils

import android.media.MediaFormat
import android.os.Build
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

/**
 * Tests for color metadata handling in encoder format configuration.
 * Ensures BT.709 defaults are used when input metadata is missing.
 */
class ColorMetadataTest {
    private fun createInputFormat(
        hasColorStandard: Boolean = false,
        colorStandard: Int = 0,
        hasColorTransfer: Boolean = false,
        colorTransfer: Int = 0,
        hasColorRange: Boolean = false,
        colorRange: Int = 0,
    ): MediaFormat =
        mockk(relaxed = true) {
            every { containsKey(MediaFormat.KEY_COLOR_STANDARD) } returns hasColorStandard
            if (hasColorStandard) {
                every { getInteger(MediaFormat.KEY_COLOR_STANDARD) } returns colorStandard
            }
            every { containsKey(MediaFormat.KEY_COLOR_TRANSFER) } returns hasColorTransfer
            if (hasColorTransfer) {
                every { getInteger(MediaFormat.KEY_COLOR_TRANSFER) } returns colorTransfer
            }
            every { containsKey(MediaFormat.KEY_COLOR_RANGE) } returns hasColorRange
            if (hasColorRange) {
                every { getInteger(MediaFormat.KEY_COLOR_RANGE) } returns colorRange
            }
            every { containsKey(MediaFormat.KEY_FRAME_RATE) } returns true
            every { getInteger(MediaFormat.KEY_FRAME_RATE) } returns 30
            every { containsKey(MediaFormat.KEY_I_FRAME_INTERVAL) } returns false
        }

    private fun createOutputFormat(): MediaFormat =
        mockk(relaxed = true) {
            every { getString(MediaFormat.KEY_MIME) } returns "video/avc"
        }

    @Test
    fun `setOutputFileParameters - copies color standard from input when present`() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return

        val inputFormat =
            createInputFormat(
                hasColorStandard = true,
                colorStandard = MediaFormat.COLOR_STANDARD_BT601_NTSC,
            )
        val outputFormat = createOutputFormat()

        CompressorUtils.setOutputFileParameters(
            inputFormat = inputFormat,
            outputFormat = outputFormat,
            newBitrate = 2_000_000L,
        )

        verify {
            outputFormat.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT601_NTSC)
        }
    }

    @Test
    fun `setOutputFileParameters - defaults to BT709 when color standard missing`() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return

        val inputFormat = createInputFormat()
        val outputFormat = createOutputFormat()

        CompressorUtils.setOutputFileParameters(
            inputFormat = inputFormat,
            outputFormat = outputFormat,
            newBitrate = 2_000_000L,
        )

        verify {
            outputFormat.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
        }
    }

    @Test
    fun `setOutputFileParameters - copies color transfer from input when present`() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return

        val inputFormat =
            createInputFormat(
                hasColorTransfer = true,
                colorTransfer = MediaFormat.COLOR_TRANSFER_HLG,
            )
        val outputFormat = createOutputFormat()

        CompressorUtils.setOutputFileParameters(
            inputFormat = inputFormat,
            outputFormat = outputFormat,
            newBitrate = 2_000_000L,
        )

        verify {
            outputFormat.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
        }
    }

    @Test
    fun `setOutputFileParameters - defaults to SDR_VIDEO when color transfer missing`() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return

        val inputFormat = createInputFormat()
        val outputFormat = createOutputFormat()

        CompressorUtils.setOutputFileParameters(
            inputFormat = inputFormat,
            outputFormat = outputFormat,
            newBitrate = 2_000_000L,
        )

        verify {
            outputFormat.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }
    }

    @Test
    fun `setOutputFileParameters - always sets limited color range`() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return

        val inputFormat =
            createInputFormat(
                hasColorRange = true,
                colorRange = MediaFormat.COLOR_RANGE_FULL,
            )
        val outputFormat = createOutputFormat()

        CompressorUtils.setOutputFileParameters(
            inputFormat = inputFormat,
            outputFormat = outputFormat,
            newBitrate = 2_000_000L,
        )

        verify {
            outputFormat.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
        }
    }

    @Test
    fun `setOutputFileParameters - preserves all color metadata when present`() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return

        val inputFormat =
            createInputFormat(
                hasColorStandard = true,
                colorStandard = MediaFormat.COLOR_STANDARD_BT709,
                hasColorTransfer = true,
                colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                hasColorRange = true,
                colorRange = MediaFormat.COLOR_RANGE_LIMITED,
            )
        val outputFormat = createOutputFormat()

        CompressorUtils.setOutputFileParameters(
            inputFormat = inputFormat,
            outputFormat = outputFormat,
            newBitrate = 2_000_000L,
        )

        verify {
            outputFormat.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            outputFormat.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            outputFormat.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
        }
    }
}
