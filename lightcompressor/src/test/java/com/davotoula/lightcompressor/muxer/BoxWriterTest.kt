package com.davotoula.lightcompressor.muxer

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BoxWriterTest {
    @Test
    fun `writeBox - empty box has 8-byte header`() {
        val out = ByteArrayOutputStream()
        val writer = BoxWriter(out)
        writer.box("free") {}
        val bytes = out.toByteArray()
        assertEquals(8, bytes.size)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        assertEquals(8, bb.getInt()) // size
        assertEquals("free", String(bytes, 4, 4, Charsets.US_ASCII)) // type
    }

    @Test
    fun `writeBox - box with payload includes payload in size`() {
        val out = ByteArrayOutputStream()
        val writer = BoxWriter(out)
        writer.box("mdat") {
            writeBytes(byteArrayOf(0x01, 0x02, 0x03))
        }
        val bytes = out.toByteArray()
        assertEquals(11, bytes.size) // 8 header + 3 payload
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        assertEquals(11, bb.getInt())
    }

    @Test
    fun `fullBox - includes version and flags`() {
        val out = ByteArrayOutputStream()
        val writer = BoxWriter(out)
        writer.fullBox("mvhd", version = 1, flags = 0) {
            writeUInt32(0L) // some data
        }
        val bytes = out.toByteArray()
        // 8 (header) + 4 (version+flags) + 4 (data) = 16
        assertEquals(16, bytes.size)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        assertEquals(16, bb.getInt()) // size
        assertEquals("mvhd", String(bytes, 4, 4, Charsets.US_ASCII))
        bb.getInt() // advance past type
        assertEquals(1, bb.get().toInt()) // version
        // flags = 3 bytes, all zero
        assertEquals(0, bb.get().toInt())
        assertEquals(0, bb.get().toInt())
        assertEquals(0, bb.get().toInt())
    }

    @Test
    fun `nested boxes - outer size includes inner box`() {
        val out = ByteArrayOutputStream()
        val writer = BoxWriter(out)
        writer.box("moov") {
            box("mvhd") {
                writeUInt32(42L)
            }
        }
        val bytes = out.toByteArray()
        // outer: 8 header + inner(8 header + 4 data) = 20
        assertEquals(20, bytes.size)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        assertEquals(20, bb.getInt()) // outer size
        assertEquals("moov", String(bytes, 4, 4, Charsets.US_ASCII))
        bb.getInt() // advance past "moov" type
        assertEquals(12, bb.getInt()) // inner size
        assertEquals("mvhd", String(bytes, 12, 4, Charsets.US_ASCII))
    }

    @Test
    fun `writeUInt16 - writes big-endian 16-bit value`() {
        val out = ByteArrayOutputStream()
        val writer = BoxWriter(out)
        writer.box("test") {
            writeUInt16(0x0102)
        }
        val bytes = out.toByteArray()
        assertEquals(0x01.toByte(), bytes[8])
        assertEquals(0x02.toByte(), bytes[9])
    }

    @Test
    fun `writeUInt32 - writes big-endian 32-bit value`() {
        val out = ByteArrayOutputStream()
        val writer = BoxWriter(out)
        writer.box("test") {
            writeUInt32(0x01020304L)
        }
        val bytes = out.toByteArray()
        assertEquals(0x01.toByte(), bytes[8])
        assertEquals(0x02.toByte(), bytes[9])
        assertEquals(0x03.toByte(), bytes[10])
        assertEquals(0x04.toByte(), bytes[11])
    }

    @Test
    fun `writeUInt64 - writes big-endian 64-bit value`() {
        val out = ByteArrayOutputStream()
        val writer = BoxWriter(out)
        writer.box("test") {
            writeUInt64(0x0102030405060708L)
        }
        val bytes = out.toByteArray()
        assertEquals(0x01.toByte(), bytes[8])
        assertEquals(0x08.toByte(), bytes[15])
    }

    @Test
    fun `writeFourCC - writes 4 ASCII bytes`() {
        val out = ByteArrayOutputStream()
        val writer = BoxWriter(out)
        writer.box("test") {
            writeFourCC("isom")
        }
        val bytes = out.toByteArray()
        assertEquals("isom", String(bytes, 8, 4, Charsets.US_ASCII))
    }
}
