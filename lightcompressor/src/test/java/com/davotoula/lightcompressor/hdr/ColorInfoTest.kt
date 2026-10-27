package com.davotoula.lightcompressor.hdr

import android.media.MediaFormat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class ColorInfoTest {
    private fun format(
        standard: Int? = null,
        transfer: Int? = null,
        range: Int? = null,
        staticInfo: ByteArray? = null,
    ): MediaFormat {
        val f = mockk<MediaFormat>(relaxed = true)

        fun stub(
            key: String,
            value: Int?,
        ) {
            every { f.containsKey(key) } returns (value != null)
            if (value != null) every { f.getInteger(key) } returns value
        }
        stub(MediaFormat.KEY_COLOR_STANDARD, standard)
        stub(MediaFormat.KEY_COLOR_TRANSFER, transfer)
        stub(MediaFormat.KEY_COLOR_RANGE, range)
        every { f.containsKey(MediaFormat.KEY_HDR_STATIC_INFO) } returns (staticInfo != null)
        if (staticInfo != null) {
            every { f.getByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO) } returns ByteBuffer.wrap(staticInfo)
        }
        return f
    }

    private fun blob(
        size: Int = 25,
        mastering: Int = 1000,
        cll: Int = 650,
    ): ByteArray {
        val b = ByteArray(size)
        if (size >= 23) {
            b[17] = (mastering and 0xFF).toByte()
            b[18] = ((mastering shr 8) and 0xFF).toByte()
            b[21] = (cll and 0xFF).toByte()
            b[22] = ((cll shr 8) and 0xFF).toByte()
        }
        return b
    }

    @Test
    fun hlg_isClassified() {
        val info = ColorInfo.from(format(transfer = MediaFormat.COLOR_TRANSFER_HLG), 33)
        assertTrue(info.isHlg)
        assertTrue(info.isHdr)
        assertFalse(info.isPq)
    }

    @Test
    fun pq_isClassified() {
        val info = ColorInfo.from(format(transfer = MediaFormat.COLOR_TRANSFER_ST2084), 33)
        assertTrue(info.isPq)
        assertTrue(info.isHdr)
        assertFalse(info.isHlg)
    }

    @Test
    fun sdr_isNotHdr() {
        val info = ColorInfo.from(format(transfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO), 33)
        assertFalse(info.isHdr)
    }

    @Test
    fun missingTags_areNull() {
        val info = ColorInfo.from(format(), 33)
        assertNull(info.standard)
        assertNull(info.transfer)
        assertNull(info.range)
        assertFalse(info.isHdr)
    }

    @Test
    fun belowApi24_everythingNull() {
        val f =
            format(
                standard = MediaFormat.COLOR_STANDARD_BT2020,
                transfer = MediaFormat.COLOR_TRANSFER_HLG,
                range = MediaFormat.COLOR_RANGE_LIMITED,
                staticInfo = blob(),
            )
        val info = ColorInfo.from(f, 23)
        assertNull(info.standard)
        assertNull(info.transfer)
        assertNull(info.range)
        assertNull(info.staticInfo)
        verify(exactly = 0) { f.getInteger(any()) }
    }

    @Test
    fun staticInfo_parsesMaxCllAndMastering() {
        val info = ColorInfo.from(format(transfer = MediaFormat.COLOR_TRANSFER_ST2084, staticInfo = blob()), 33)
        assertEquals(1000, info.maxMasteringLuminance)
        assertEquals(650, info.maxContentLightLevel)
    }

    @Test
    fun staticInfo_wrongSize_isNull() {
        val info = ColorInfo.from(format(staticInfo = blob(size = 24)), 33)
        assertNull(info.maxMasteringLuminance)
        assertNull(info.maxContentLightLevel)
    }

    @Test
    fun staticInfo_zeroValues_areNull() {
        val info = ColorInfo.from(format(staticInfo = blob(mastering = 0, cll = 0)), 33)
        assertNull(info.maxMasteringLuminance)
        assertNull(info.maxContentLightLevel)
    }
}
