package com.davotoula.lightcompressor

internal const val DEFAULT_FAILURE_MESSAGE = "An error has occurred!"

/**
 * `<Class>: <message>`, or the class alone when the message is null or blank. Never blank. Uses the
 * simple class name: the string is shown to users as well as logged.
 */
internal fun Throwable.describe(): String {
    val name = javaClass.simpleName.ifEmpty { javaClass.name }
    val detail = message?.trim().orEmpty()
    return if (detail.isEmpty()) name else "$name: $detail"
}

/** [message] trimmed, unless it is null or blank, else [DEFAULT_FAILURE_MESSAGE]. */
internal fun failureMessageOrDefault(message: String?): String =
    message?.trim()?.takeUnless { it.isEmpty() } ?: DEFAULT_FAILURE_MESSAGE
