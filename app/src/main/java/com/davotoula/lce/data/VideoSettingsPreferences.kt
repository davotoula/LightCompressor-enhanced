package com.davotoula.lce.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.davotoula.lce.ui.Codec
import com.davotoula.lightcompressor.Resolution
import com.davotoula.lightcompressor.config.HdrMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.videoSettingsDataStore by preferencesDataStore(name = "video_settings_preferences")

data class VideoSettings(
    val resolution: Resolution = Resolution.HD_720,
    val codec: Codec = Codec.H264,
    val isStreamableEnabled: Boolean = true,
    val bitrateKbps: Int? = null,
    val hlsCodec: Codec = Codec.H264,
    val hdrMode: HdrMode = HdrMode.KEEP_IF_SUPPORTED,
)

class VideoSettingsPreferences(
    private val context: Context,
) {
    private val resolutionKey = stringPreferencesKey("resolution")
    private val codecKey = stringPreferencesKey("codec")
    private val streamableKey = booleanPreferencesKey("streamable")
    private val bitrateKey = intPreferencesKey("bitrate_kbps")
    private val hlsCodecKey = stringPreferencesKey("hls_codec")
    private val hdrModeKey = stringPreferencesKey("hdr_mode")

    val settings: Flow<VideoSettings> =
        context.videoSettingsDataStore.data.map { prefs ->
            VideoSettings(
                resolution =
                    prefs[resolutionKey]?.let { name ->
                        Resolution.entries.find { it.name == name }
                    } ?: Resolution.HD_720,
                codec =
                    prefs[codecKey]?.let { name ->
                        Codec.entries.find { it.name == name }
                    } ?: Codec.H264,
                isStreamableEnabled = prefs[streamableKey] ?: true,
                bitrateKbps = prefs[bitrateKey],
                hlsCodec =
                    prefs[hlsCodecKey]?.let { name ->
                        Codec.entries.find { it.name == name }
                    } ?: Codec.H264,
                hdrMode =
                    prefs[hdrModeKey]
                        ?.let { runCatching { HdrMode.valueOf(it) }.getOrNull() }
                        ?: HdrMode.KEEP_IF_SUPPORTED,
            )
        }

    suspend fun saveSettings(
        resolution: Resolution,
        codec: Codec,
        isStreamableEnabled: Boolean,
        bitrateKbps: Int,
        hdrMode: HdrMode,
    ) {
        context.videoSettingsDataStore.edit { prefs ->
            prefs[resolutionKey] = resolution.name
            prefs[codecKey] = codec.name
            prefs[streamableKey] = isStreamableEnabled
            prefs[bitrateKey] = bitrateKbps
            prefs[hdrModeKey] = hdrMode.name
        }
    }

    suspend fun saveHlsCodec(hlsCodec: Codec) {
        context.videoSettingsDataStore.edit { prefs ->
            prefs[hlsCodecKey] = hlsCodec.name
        }
    }
}
