[![JitPack](https://jitpack.io/v/davotoula/LightCompressor-enhanced.svg)](https://jitpack.io/#davotoula/LightCompressor-enhanced)
![GitHub Downloads (all assets, all releases)](https://img.shields.io/github/downloads/davotoula/LightCompressor-enhanced/total)

## Download and Install app
[![Obtanium](https://raw.githubusercontent.com/vitorpamplona/amethyst/main/docs/design/obtainium.png)](https://obtainium.imranr.dev/)[![ZapStore](https://raw.githubusercontent.com/vitorpamplona/amethyst/main/docs/design/zapstore.svg)](https://zapstore.dev/apps)


# Purpose of fork
## New API Features:

1. **H.265 (HEVC) Video Encoding Support**: Choose between H.264 (AVC) and H.265 (HEVC) codecs for video compression
   - VideoCodec.H264 - Default, maximum compatibility
   - VideoCodec.H265 - Better compression efficiency, smaller file sizes (where supported)
   - Configuration.videoCodec field for codec selection

2. New Configuration Field: videoBitrateInBps: Long? for granular bitrate control
3. Priority System: BPS takes precedence over Mbps when both are specified
4. Helper Methods:
   - Configuration.withBitrateInBps() - Creates config with bps bitrate
   - Configuration.withBitrateInMbps() - Creates config with Mbps bitrate (legacy)
   - getEffectiveBitrateInBps() - Internal method to resolve bitrate

## Key Benefits:

- Replaced 3rd party mp4 muxer with native android muxer
- **H.265 Encoding**: Up to 50% better compression than H.264 at the same quality level
- **Automatic Device Validation**: Library automatically checks H.265 support and returns clear error if unsupported
- **Device Compatibility Check**: Built-in utility to verify H.265 encoder availability before compression
- Granular Control: Allows bitrates like 1,500,000 bps (1.5 Mbps) instead of being limited to whole Mbps values
- Sub-Mbps Bitrates: Enable bitrates lower than 1 Mbps for extreme compression scenarios
- Better Precision: Match MediaCodec's native bps format exactly
- Backward Compatible: Existing Mbps API continues to work unchanged


## Technical Implementation:

- Updated internal compression logic to handle Long bitrate values
- Modified MediaFormat parameter setting with proper type conversion
- Added validation for positive bitrate values
- Updated documentation and examples

## TODO
- [ ] Rewrite app to use JetPack Compose
- [x] Publish app to Zapstore
- [ ] Publish app to Google Play
- [ ] Publish library to maven repo
- [ ] Performance improvements
- [ ] Compress large files test (200mb+)

## Usage Examples:

```kotlin
// Option 1: Check H.265 support first (recommended for better UX)
import com.abedelazizshe.lightcompressorlibrary.utils.CompressorUtils

val config = if (CompressorUtils.isHevcEncodingSupported()) {
    // Use H.265 for up to 50% better compression
    Configuration.withBitrateInBps(
        quality = VideoQuality.MEDIUM,
        videoBitrateInBps = 1500000L,
        videoNames = listOf("video.mp4"),
        videoCodec = VideoCodec.H265
    )
} else {
    // Fallback to H.264
    Configuration.withBitrateInBps(
        quality = VideoQuality.MEDIUM,
        videoBitrateInBps = 1500000L,
        videoNames = listOf("video.mp4"),
        videoCodec = VideoCodec.H264
    )
}

// Option 2: Let the library validate (error returned in onFailure callback)
val h265Config = Configuration.withBitrateInBps(
    quality = VideoQuality.MEDIUM,
    videoBitrateInBps = 1500000L,
    videoNames = listOf("video.mp4"),
    videoCodec = VideoCodec.H265
)
// If device doesn't support H.265, onFailure will be called with:
// "H.265 (HEVC) encoding is not supported on this device. Please use VideoCodec.H264 instead."

// Default H.264 encoding (maximum compatibility)
val h264Config = Configuration.withBitrateInBps(
    quality = VideoQuality.MEDIUM,
    videoBitrateInBps = 1500000L,
    videoNames = listOf("video.mp4"),
    videoCodec = VideoCodec.H264 // Default codec
)

// Legacy Mbps API still works (H.264 by default)
val legacyConfig = Configuration.withBitrateInMbps(
    quality = VideoQuality.HIGH,
    videoBitrateInMbps = 2,
    videoNames = listOf("video.mp4")
)
```

# LightCompressor

LightCompressor can now be used in Flutter through [light_compressor](https://pub.dev/packages/light_compressor) plugin.

A powerful and easy-to-use video compression library for android uses [MediaCodec](https://developer.android.com/reference/android/media/MediaCodec) API. This library generates a compressed MP4 video with a modified width, height, and bitrate (the number of bits per
seconds that determines the video and audio files’ size and quality). It is based on Telegram for Android project.

The general idea of how the library works is that, extreme high bitrate is reduced while maintaining a good video quality resulting in a smaller size.

I would like to mention that the set attributes for size and quality worked just great in my projects and met the expectations. It may or may not meet yours. I’d appreciate your feedback so I can enhance the compression process.

**LightCompressor is now available in iOS**, have a look at [LightCompressor_iOS](https://github.com/AbedElazizShe/LightCompressor_iOS).

# Change Logs

## What's new in 1.3.3

- Thanks to [LiewJunTung](https://github.com/AbedElazizShe/LightCompressor/pull/181) for improving the error handling.
- Thanks to [CristianMG](https://github.com/AbedElazizShe/LightCompressor/pull/182) for improving the storage configuration and making the library testable.
- Thanks to [dan3988](https://github.com/AbedElazizShe/LightCompressor/pull/188) for replacing video size with resizer which made using the library way more flexible.
- Thanks to [imSzukala](https://github.com/AbedElazizShe/LightCompressor/pull/191) for changing min supported api to 21.
- Thanks to [josebraz](https://github.com/AbedElazizShe/LightCompressor/pull/192) for improving codec profile approach.
- Thanks to [ryccoatika](https://github.com/AbedElazizShe/LightCompressor/pull/198) for improving exception handling for the coroutines.


## How it works
When the video file is called to be compressed, the library checks if the user wants to set a min bitrate to avoid compressing low resolution videos. This becomes handy if you don’t want the video to be compressed every time it is to be processed to avoid having very bad quality after multiple rounds of compression. The minimum is;
* Bitrate: 2mbps

You can as well pass custom resizer and videoBitrate values if you don't want the library to auto-generate the values for you.

These values were tested on a huge set of videos and worked fine and fast with them. They might be changed based on the project needs and expectations.

## Demo
![Demo](/pictures/demo.gif)

Usage
--------
To use this library, you must add the following permission to allow read and write to external storage. Refer to the sample app for a reference on how to start compression with the right setup.

**API < 29**

```xml
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"/>
<uses-permission
    android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="28"
    tools:ignore="ScopedStorage" />
```

**API >= 29**

```xml
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
    android:maxSdkVersion="32"/>
```

**API >= 33**

```xml
 <uses-permission android:name="android.permission.READ_MEDIA_VIDEO"/>
```

```kotlin

 if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
     // request READ_MEDIA_VIDEO run-time permission
 } else {
     // request WRITE_EXTERNAL_STORAGE run-time permission
 }
```

And import the following dependencies to use kotlin coroutines

### Groovy

```groovy
implementation "org.jetbrains.kotlinx:kotlinx-coroutines-core:${Version.coroutines}"
implementation "org.jetbrains.kotlinx:kotlinx-coroutines-android:${Version.coroutines}"
```

Then just call [VideoCompressor.start()] and pass **context**, **uris**, **isStreamable**, **configureWith**, and either **sharedStorageConfiguration OR appSpecificStorageConfiguration**.

The method has a callback for 5 functions;
1) OnStart - called when compression started
2) OnSuccess - called when compression completed with no errors/exceptions
3) OnFailure - called when an exception occurred or video bitrate and size are below the minimum required for compression.
4) OnProgress - called with progress new value
5) OnCancelled - called when the job is cancelled

### Important Notes:

- All the callback functions returns an index for the video being compressed in the same order of the urls passed to the library. You can use this index to update the UI
or retrieve information about the original uri/file.
- The source video must be provided as a list of content uris.
- OnSuccess returns the path of the stored video.
- If you want an output video that is optimised to be streamed, ensure you pass [isStreamable] flag is true.

### Configuration values

- VideoQuality: VERY_HIGH (original-bitrate * 0.6) , HIGH (original-bitrate * 0.4), MEDIUM (original-bitrate * 0.3), LOW (original-bitrate * 0.2), OR VERY_LOW (original-bitrate * 0.1)

- isMinBitrateCheckEnabled: this means, don't compress if bitrate is less than 2mbps

- videoBitrateInMbps: any custom bitrate value in Mbps.

- videoBitrateInBps: any custom bitrate value in bps (takes precedence over videoBitrateInMbps).

- videoCodec: VideoCodec.H264 (default) or VideoCodec.H265 for HEVC encoding.

- disableAudio: true/false to generate a video without audio. False by default.

- resizer: Function to resize the video dimensions. `VideoResizer.auto` by default.


## The StorageConfiguration is an interface which indicate library where will be saved the File

#### Library provides some behaviors defined to be more easy to use, specified the next

### AppSpecificStorageConfiguration Configuration values

- subFolderName: a subfolder name created in app's specific storage. 

### SharedStorageConfiguration Configuration values

- saveAt: the directory where the video should be saved in. Must be one of the following; [SaveLocation.pictures], [SaveLocation.movies], or [SaveLocation.downloads].
- subFolderName: a subfolder name created in shared storage. 

### CacheStorageConfiguration
- There are no configuration values create a file in cache directory as Google defined, to get more info go to [here](https://developer.android.com/training/data-storage/app-specific?hl=es-419)

### Fully custom configuration
- If any of these behaviors fit with your needs, you can create your own StorageConfiguration, just implement the interface and pass it to the library

```kotlin
class FullyCustomizedStorageConfiguration(
) : StorageConfiguration {
    override fun createFileToSave(
        context: Context,
        videoFile: File,
        fileName: String,
        shouldSave: Boolean
    ): File = ??? What you need 
}

```

To cancel the compression job, just call [VideoCompressor.cancel()]

### Kotlin

```kotlin
VideoCompressor.start(
   context = applicationContext, // => This is required
   uris = List<Uri>, // => Source can be provided as content uris
   isStreamable = false,
   // THIS STORAGE
   storageConfiguration = SharedStorageConfiguration(
       saveAt = SaveLocation.movies, // => default is movies
       subFolderName = "my-videos" // => optional
   )
   configureWith = Configuration(
      videoNames = listOf<String>(), /*list of video names, the size should be similar to the passed uris*/
      quality = VideoQuality.MEDIUM,
      isMinBitrateCheckEnabled = true,
      videoBitrateInMbps = 5, /*Int, ignore, or null*/
      disableAudio = false, /*Boolean, or ignore*/
      resizer = VideoResizer.matchSize(360, 480), /*VideoResizer, ignore, or null*/
      videoCodec = VideoCodec.H264 /*VideoCodec.H264 (default) or VideoCodec.H265*/
   ),
   listener = object : CompressionListener {
       override fun onProgress(index: Int, percent: Float) {
          // Update UI with progress value
          runOnUiThread {
          }
       }

       override fun onStart(index: Int) {
          // Compression start
       }

       override fun onSuccess(index: Int, size: Long, path: String?) {
         // On Compression success
       }

       override fun onFailure(index: Int, failureMessage: String) {
         // On Failure
       }

       override fun onCancelled(index: Int) {
         // On Cancelled
       }

   }
)
```

## Common issues

- Sending the video to whatsapp when disableAudio = false, won't succeed [ at least for now ]. Whatsapp's own compression does not work with
LightCompressor library. You can send the video as document.

- You cannot call Toast.makeText() and other functions dealing with the UI directly in onProgress() which is a worker thread. They need to be called
from within the main thread. Have a look at the example code above for more information.

## Reporting issues
To report an issue, please specify the following:
- Device name
- Android version

## Compatibility
Minimum Android SDK: LightCompressor requires a minimum API level of 21.

## How to add to your project?
#### Gradle

Ensure Kotlin version is `1.8.21`

Include this in your Project-level build.gradle file:

### Groovy

```groovy
allprojects {
    repositories {
        .
        .
        .
        maven { url 'https://jitpack.io' }
    }
}
```

Include this in your Module-level build.gradle file:

### Groovy

```groovy
implementation 'com.github.davotoula:LightCompressor-enhanced:1.4.0'
```

If you're facing problems with the setup, edit settings.gradle by adding this at the beginning of the file:

```
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

## Getting help
For questions, suggestions, or anything else, email elaziz.shehadeh(at)gmail.com

## Credits
[Telegram](https://github.com/DrKLO/Telegram) for Android.
