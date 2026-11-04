package com.davotoula.lightcompressor

/** What [CompressionListener.onFailure] gets when a failure carries no reason of its own. */
internal const val DEFAULT_FAILURE_MESSAGE = "An error has occurred!"

/**
 * Names this throwable for a failure report: `<class>: <message>`, or the class alone when the
 * message is null or blank. Never blank, so a report always says at least what was thrown.
 */
internal fun Throwable.describe(): String {
    val detail = message?.trim().orEmpty()
    return if (detail.isEmpty()) javaClass.name else "${javaClass.name}: $detail"
}

/** [message] unless it is null or blank, else [DEFAULT_FAILURE_MESSAGE]. */
internal fun failureMessageOrDefault(message: String?): String =
    message?.takeUnless { it.isBlank() } ?: DEFAULT_FAILURE_MESSAGE
