package com.davotoula.lce

import com.google.firebase.Firebase
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.analytics
import com.google.firebase.analytics.logEvent
import com.google.firebase.crashlytics.crashlytics

object AnalyticsTracker {

    private const val PARAM_SOURCE = "source"
    private const val PARAM_COUNT = "count"
    private const val PARAM_CODEC = "codec"
    private const val PARAM_BITRATE = "bitrate_kbps"
    private const val PARAM_MAX_RESOLUTION = "max_resolution"
    private const val PARAM_STREAMABLE = "streamable"
    private const val PARAM_STATUS = "status"
    private const val PARAM_PATH_PRESENT = "path_present"

    fun logAppOpen() {
        Firebase.analytics.logEvent(FirebaseAnalytics.Event.APP_OPEN, null)
    }

    fun logVideoSelection(source: String, count: Int) {
        Firebase.analytics.logEvent("video_selection") {
            param(PARAM_SOURCE, source)
            param(PARAM_COUNT, count.toLong())
        }
    }

    fun logCompressionStarted(
        bitrateKbps: Long,
        maxResolution: Int,
        codec: String,
        streamable: Boolean,
        videoCount: Int,
        source: String,
    ) {
        Firebase.analytics.logEvent("compression_started") {
            param(PARAM_BITRATE, bitrateKbps)
            param(PARAM_MAX_RESOLUTION, maxResolution.toLong())
            param(PARAM_CODEC, codec)
            param(PARAM_STREAMABLE, if (streamable) 1L else 0L)
            param(PARAM_COUNT, videoCount.toLong())
            param(PARAM_SOURCE, source)
        }
    }

    fun logCompressionResult(
        status: String,
        codec: String,
        source: String,
        videoCount: Int,
    ) {
        Firebase.analytics.logEvent("compression_result") {
            param(PARAM_STATUS, status)
            param(PARAM_CODEC, codec)
            param(PARAM_SOURCE, source)
            param(PARAM_COUNT, videoCount.toLong())
        }
    }

    fun logCompressionCancelled(source: String, videoCount: Int) {
        Firebase.analytics.logEvent("compression_cancelled") {
            param(PARAM_SOURCE, source)
            param(PARAM_COUNT, videoCount.toLong())
        }
    }

    fun recordCompressionFailure(
        message: String,
        codec: String,
        bitrateKbps: Long,
        streamable: Boolean,
        source: String,
    ) {
        Firebase.crashlytics.setCustomKey(PARAM_CODEC, codec)
        Firebase.crashlytics.setCustomKey(PARAM_BITRATE, bitrateKbps)
        Firebase.crashlytics.setCustomKey(PARAM_STREAMABLE, streamable)
        Firebase.crashlytics.setCustomKey(PARAM_SOURCE, source)
        Firebase.crashlytics.log("compression_failure: $message")
        Firebase.crashlytics.recordException(CompressionException(message))

        Firebase.analytics.logEvent("compression_failure") {
            param(PARAM_CODEC, codec)
            param(PARAM_SOURCE, source)
            param(PARAM_BITRATE, bitrateKbps)
            param(PARAM_STREAMABLE, if (streamable) 1L else 0L)
        }
    }

    fun logVideoPlayback(uri: String?) {
        Firebase.analytics.logEvent("video_playback") {
            param(PARAM_PATH_PRESENT, if (uri.isNullOrEmpty()) 0L else 1L)
        }
    }
}

private class CompressionException(message: String) : RuntimeException(message)
