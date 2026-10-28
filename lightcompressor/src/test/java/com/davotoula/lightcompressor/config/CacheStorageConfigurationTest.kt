package com.davotoula.lightcompressor.config

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CacheStorageConfigurationTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val context: Context by lazy {
        mockk<Context>().also { every { it.cacheDir } returns tempFolder.root }
    }
    private val storage = CacheStorageConfiguration()

    @Test
    fun `before compression the output is cacheDir slash fileName`() {
        // A one-character name: File.createTempFile, used before, needs a 3-character prefix.
        val file = storage.createFileToSave(context, File("/src/a.mp4"), "a.mp4", shouldSave = false)

        assertEquals(File(tempFolder.root, "a.mp4"), file)
        assertFalse("compression creates the file, not the configuration", file.exists())
    }

    @Test
    fun `after compression the compressed file is returned as is`() {
        val compressed = tempFolder.newFile("out.mp4")

        val file = storage.createFileToSave(context, compressed, "out.mp4", shouldSave = true)

        assertEquals(compressed, file)
    }

    @Test
    fun `a name with a subfolder gets its parent created`() {
        val file = storage.createFileToSave(context, File("/src/clip.mp4"), "sub/out.mp4", shouldSave = false)

        assertEquals(File(tempFolder.root, "sub/out.mp4"), file)
        assertTrue(File(tempFolder.root, "sub").isDirectory)
    }
}
