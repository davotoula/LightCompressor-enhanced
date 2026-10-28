package com.davotoula.lce

import java.text.DecimalFormat
import kotlin.math.log10
import kotlin.math.pow

private const val FILE_SIZE_BASE = 1024.0

fun getFileSize(size: Long): String {
    if (size <= 0) {
        return "0"
    }

    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (log10(size.toDouble()) / log10(FILE_SIZE_BASE)).toInt()

    return DecimalFormat("#,##0.#").format(
        size / 1024.0.pow(digitGroups.toDouble()),
    ) + " " + units[digitGroups]
}
