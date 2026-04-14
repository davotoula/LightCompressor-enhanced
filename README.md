[![](https://jitpack.io/v/davotoula/LightCompressor-enhanced.svg)](https://jitpack.io/#davotoula/LightCompressor-enhanced)
![GitHub Downloads (all assets, all releases)](https://img.shields.io/github/downloads/davotoula/LightCompressor-enhanced/total)

# LightCompressor Enhanced

A powerful and easy-to-use Android video compression library using MediaCodec. Generates compressed MP4 with configurable resolution, bitrate, and codec while maintaining good visual quality.

## Installation

Add the JitPack repository to your `settings.gradle`:

```groovy
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

Add the dependency in your module-level `build.gradle`:

```groovy
implementation 'com.github.davotoula:LightCompressor-enhanced:Tag'
```

You also need Kotlin coroutines:

```groovy
implementation "org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutines_version"
implementation "org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutines_version"
```

## Quick Start

```kotlin
import com.davotoula.lightcompressor.VideoCompressor
import com.davotoula.lightcompressor.VideoQuality
import com.davotoula.lightcompressor.config.Configuration
import com.davotoula.lightcompressor.config.SharedStorageConfiguration
import com.davotoula.lightcompressor.config.SaveLocation
import com.davotoula.lightcompressor.listener.CompressionListener

VideoCompressor.start(
    context = applicationContext,
    uris = listOf(videoUri),
    isStreamable = true,
    storageConfiguration = SharedStorageConfiguration(
        saveAt = SaveLocation.movies,
        subFolderName = "my-videos"
    ),
    configureWith = Configuration(
        videoNames = listOf("output.mp4"),
        quality = VideoQuality.MEDIUM,
        isMinBitrateCheckEnabled = true,
        disableAudio = false,
    ),
    listener = object : CompressionListener {
        override fun onStart(index: Int) {
            // Compression started
        }

        override fun onProgress(index: Int, percent: Float) {
            // Update progress UI (worker thread — post to main thread if needed)
        }

        override fun onSuccess(index: Int, size: Long, path: String?) {
            // Compression finished; path is the output file location
        }

        override fun onFailure(index: Int, failureMessage: String) {
            // Handle error
        }

        override fun onCancelled(index: Int) {
            // Compression was cancelled
        }
    }
)

// To cancel a running compression:
VideoCompressor.cancel()
```

## Features

- H.264 (AVC) and H.265 (HEVC) codec support
- Flexible `VideoResizer` API for resolution control:
  - `VideoResizer.auto` — auto-resize based on original dimensions
  - `VideoResizer.scale(0.5)` — scale by percentage
  - `VideoResizer.limitSize(1920.0)` — limit longest side (landscape-oriented bounding box)
  - `VideoResizer.limitSize(1920.0, 1080.0)` — limit width and height independently
  - `VideoResizer.limitShortSide(1080.0)` — constrain the shorter dimension, orientation-agnostic (preferred for portrait/landscape-neutral targeting)
  - `VideoResizer.limitShortSide(1920.0, 1080.0)` — same as above, uses the smaller of the two values
  - `VideoResizer.matchSize(1920.0, 1080.0)` — scale to match target dimensions exactly
- GIF to MP4 conversion
- Streamable output (moov atom moved for progressive download)
- Granular bitrate control: specify in Mbps (`videoBitrateInMbps`) or bps (`videoBitrateInBps`)
- Audio control (`disableAudio`)
- Native Android `MediaMuxer` (no third-party MP4 muxer)

## Configuration

### VideoQuality

| Quality    | Bitrate multiplier |
|------------|--------------------|
| VERY_HIGH  | 0.6x original      |
| HIGH       | 0.4x original      |
| MEDIUM     | 0.3x original      |
| LOW        | 0.2x original      |
| VERY_LOW   | 0.1x original      |

### Bitrate Options

- `videoBitrateInMbps: Int?` — custom bitrate in Mbps
- `videoBitrateInBps: Long?` — custom bitrate in bps (takes precedence over `videoBitrateInMbps`)
- `isMinBitrateCheckEnabled: Boolean` — when `true`, skips compression if source bitrate is below 2 Mbps

### Codec Selection

```kotlin
import com.davotoula.lightcompressor.VideoCodec
import com.davotoula.lightcompressor.utils.CompressorUtils

val codec = if (CompressorUtils.isHevcEncodingSupported()) {
    VideoCodec.H265  // Better compression, smaller files
} else {
    VideoCodec.H264  // Maximum device compatibility (default)
}

val config = Configuration(
    videoNames = listOf("output.mp4"),
    quality = VideoQuality.MEDIUM,
    videoCodec = codec,
)
```

If `VideoCodec.H265` is requested on a device that does not support HEVC encoding, `onFailure` is called with a descriptive error message.

## Storage Options

### SharedStorageConfiguration

Saves to shared storage (Movies, Pictures, or Downloads).

```kotlin
import com.davotoula.lightcompressor.config.SharedStorageConfiguration
import com.davotoula.lightcompressor.config.SaveLocation

SharedStorageConfiguration(
    saveAt = SaveLocation.movies,  // or .pictures / .downloads
    subFolderName = "my-videos"    // optional
)
```

### AppSpecificStorageConfiguration

Saves to the app's private external storage directory.

```kotlin
import com.davotoula.lightcompressor.config.AppSpecificStorageConfiguration

AppSpecificStorageConfiguration(
    subFolderName = "compressed"  // optional subfolder
)
```

### CacheStorageConfiguration

Saves to the app's cache directory (may be cleared by the system).

```kotlin
import com.davotoula.lightcompressor.config.CacheStorageConfiguration

CacheStorageConfiguration()
```

### Custom Storage

Implement `StorageConfiguration` for full control over where files are written.

```kotlin
import com.davotoula.lightcompressor.config.StorageConfiguration

class MyStorageConfiguration : StorageConfiguration {
    override fun createFileToSave(
        context: Context,
        videoFile: File,
        fileName: String,
        shouldSave: Boolean
    ): File {
        // Return the File where the output should be written
    }
}
```

## Permissions

**API < 29**

```xml
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"/>
<uses-permission
    android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="28"
    tools:ignore="ScopedStorage" />
```

**API 29 – 32**

```xml
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
    android:maxSdkVersion="32"/>
```

**API >= 33 (Photo Picker recommended)**

```xml
<uses-permission android:name="android.permission.READ_MEDIA_VIDEO"/>
```

## HLS Preparation

`HlsPreparer` transcodes a local video into multiple HLS VOD renditions (fMP4 segments + m3u8 playlists) for adaptive-bitrate playback.

### API

- `HlsPreparer.start(context, uri, config, listener): Job` — kicks off preparation and returns a coroutine `Job`.
- `HlsPreparer.cancel()` — cancels any running preparation.
- `HlsConfig(ladder, codec, segmentDurationSeconds, disableAudio, singleFilePerRendition)` — configuration. Defaults: `HlsLadder.default()`, `VideoCodec.H264`, 6 s segments, audio enabled, multi-file output. Set `singleFilePerRendition = true` to emit one combined fMP4 file per rendition (init + every media segment) referenced by `#EXT-X-BYTERANGE` in the playlist — useful when the consumer wants a single upload per rendition instead of dozens of segment files.
- `HlsLadder` — ordered list of `Rendition`s. Use `HlsLadder.default()` (360p / 540p / 720p / 1080p / 4K) and chain `.drop("1080p", "4K")` or `.add(Rendition(Resolution.HD_720, 2500))` to customise. Renditions whose short side exceeds the source are automatically dropped at `start`.
- `Rendition(resolution: Resolution, bitrateKbps: Int)` — a single ladder entry. `Resolution` is the same enum used by `VideoCompressor` (`SD_360`, `SD_540`, `HD_720`, `FHD_1080`, `UHD_4K`).
- `HlsListener` — 8 callbacks: `onStart(renditionCount)`, `onRenditionStart(rendition)`, `onSegmentReady(rendition, segment)`, `onRenditionComplete(rendition, playlist)`, `onComplete(masterPlaylist)`, `onFailure(error)`, `onProgress(rendition, percent)`, `onCancelled()`.
- `HlsSegment(file, index, durationSeconds, isInitSegment, isCombinedRendition)` — one emitted segment. `file` is a temp file that is **valid only until `onSegmentReady` returns** — copy or upload it synchronously. In multi-file mode `isInitSegment = true` for the per-rendition `init.mp4`. In single-file mode the listener receives exactly one callback per rendition with `isCombinedRendition = true`; the file contains the init segment followed by every media fragment.
- `HlsError(message, failedRenditions, completedRenditions)` — delivered to `onFailure` when every rendition fails. Partial failures still trigger `onComplete`.

**Threading:** `onSegmentReady` and `onProgress` are invoked on a background dispatcher (`Dispatchers.Default`); all other callbacks are on the main thread.

**Output layout (multi-file, default):** segments are identified by the rendition's `Resolution.label` (e.g. `720p`). Per rendition, `HlsPreparer` emits one `init.mp4` followed by `segment_000.m4s`, `segment_001.m4s`, … The per-rendition media playlist (`media.m3u8`) is delivered as a `String` via `onRenditionComplete`, and the master playlist (`master.m3u8`) is delivered as a `String` via `onComplete`. Persisting playlists and segments to their final destination (disk, CDN, object storage) is the caller's responsibility — a typical layout is `master.m3u8` at the root with one subdirectory per rendition label containing `media.m3u8`, `init.mp4`, and the `segment_NNN.m4s` files.

**Output layout (single-file):** with `singleFilePerRendition = true`, each rendition produces one `<label>.mp4` file (e.g. `720p.mp4`) containing the init segment immediately followed by every media fragment. The media playlist references the file via `#EXT-X-MAP:URI="<label>.mp4",BYTERANGE="<initLen>@0"` and uses `#EXT-X-BYTERANGE` for each `#EXTINF` entry, so a typical persisted layout is `master.m3u8` at the root with one subdirectory per rendition label containing `media.m3u8` and `<label>.mp4`.

### Usage

```kotlin
import com.davotoula.lightcompressor.HlsPreparer
import com.davotoula.lightcompressor.VideoCodec
import com.davotoula.lightcompressor.hls.HlsConfig
import com.davotoula.lightcompressor.hls.HlsError
import com.davotoula.lightcompressor.hls.HlsLadder
import com.davotoula.lightcompressor.hls.HlsListener
import com.davotoula.lightcompressor.hls.HlsSegment
import com.davotoula.lightcompressor.hls.Rendition
import java.io.File

val outputRoot = File(context.filesDir, "hls-out").apply { mkdirs() }

val config = HlsConfig(
    ladder = HlsLadder.default().drop("4K"),
    codec = VideoCodec.H264,
    segmentDurationSeconds = 6,
    disableAudio = false,
)

HlsPreparer.start(
    context = applicationContext,
    uri = videoUri,
    config = config,
    listener = object : HlsListener {
        override fun onStart(renditionCount: Int) { /* prep UI */ }

        override fun onRenditionStart(rendition: Rendition) { /* optional */ }

        override fun onSegmentReady(rendition: Rendition, segment: HlsSegment) {
            // Called on a background thread. Copy synchronously — the temp
            // file is deleted as soon as this method returns.
            val dir = File(outputRoot, rendition.resolution.label).apply { mkdirs() }
            val name =
                when {
                    segment.isCombinedRendition -> "${rendition.resolution.label}.mp4"
                    segment.isInitSegment -> "init.mp4"
                    else -> "segment_%03d.m4s".format(segment.index)
                }
            segment.file.copyTo(File(dir, name), overwrite = true)
        }

        override fun onRenditionComplete(rendition: Rendition, playlist: String) {
            File(outputRoot, rendition.resolution.label).also { it.mkdirs() }
                .resolve("media.m3u8")
                .writeText(playlist)
        }

        override fun onComplete(masterPlaylist: String) {
            val master = File(outputRoot, "master.m3u8").apply { writeText(masterPlaylist) }
            // Hand off to ExoPlayer (requires androidx.media3:media3-exoplayer-hls):
            //   val source = HlsMediaSource.Factory(DefaultDataSource.Factory(context))
            //       .createMediaSource(MediaItem.fromUri(Uri.fromFile(master)))
            //   exoPlayer.setMediaSource(source); exoPlayer.prepare()
        }

        override fun onFailure(error: HlsError) { /* handle error */ }

        override fun onProgress(rendition: Rendition, percent: Float) { /* update UI */ }

        override fun onCancelled() { /* cleanup */ }
    },
)

// To cancel:
HlsPreparer.cancel()
```

HLS playback on the consumer side requires the Media3 HLS module:

```groovy
implementation "androidx.media3:media3-exoplayer-hls:$media3_version"
```

## Sample App

The `app/` module contains a Jetpack Compose sample app demonstrating the library. Install it via:

<a href="https://play.google.com/store/apps/details?id=com.davotoula.lce"><img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" alt="Google Play" height="80"></a>[![Obtanium](https://raw.githubusercontent.com/vitorpamplona/amethyst/main/docs/design/obtainium.png)](https://obtainium.imranr.dev/)[![ZapStore](https://raw.githubusercontent.com/vitorpamplona/amethyst/main/docs/design/zapstore.svg)](https://zapstore.dev/apps/com.davotoula.lce)

## Compatibility

Minimum Android SDK: API level 21

## Attribution

Originally forked from [AbedElazizShe/LightCompressor](https://github.com/AbedElazizShe/LightCompressor). Based on [Telegram](https://github.com/DrKLO/Telegram) for Android.

## License

[Apache License 2.0](LICENSE)
