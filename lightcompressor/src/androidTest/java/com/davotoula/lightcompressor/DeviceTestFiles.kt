package com.davotoula.lightcompressor

import android.content.Context
import org.junit.Assert.assertTrue
import java.io.File

/** A clip pushed to the test app's external files dir, e.g. `adb push orig2.mp4 <that dir>`. */
fun pushedClip(
    context: Context,
    name: String,
): File {
    val dir = context.getExternalFilesDir(null)!!
    val clip = File(dir, name)
    assertTrue("push $name to ${dir.path} first", clip.exists())
    return clip
}

/** An empty directory [name] under the test app's external files dir, wiped first. */
fun freshTestDir(
    context: Context,
    name: String,
): File =
    File(context.getExternalFilesDir(null)!!, name).apply {
        deleteRecursively()
        assertTrue("could not create $path", mkdirs())
    }
