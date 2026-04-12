# HLS Video Preparation — Design Spec

**Date:** 2026-04-12
**Branch:** feature/hls-preparation
**Status:** Approved, awaiting implementation plan

## Purpose

Add HLS VOD preparation to the LightCompressor library. Given a local video file, the library transcodes it into multiple resolution renditions, segments each into fMP4 chunks with aligned keyframes, and generates HLS playlists — enabling adaptive bitrate streaming delivery.

The library handles video preparation only. Upload (Nostr/Blossom) is the client's responsibility.

## Scope

- No live streaming — VOD only (existing local files)
- No network I/O — library produces segments and playlists, client uploads
- No new external dependencies — pure Kotlin fMP4 muxer
- Existing compression API (`VideoCompressor`) is untouched

---

## Public API

### Entry Point

`HlsPreparer` — a new top-level singleton, separate from `VideoCompressor`.

```kotlin
object HlsPreparer {
    fun start(
        context: Context,
        uri: Uri,
        config: HlsConfig = HlsConfig(),
        listener: HlsListener
    ): Job

    fun cancel()
}
```

Single URI input. One video produces multiple renditions.

### Configuration

```kotlin
data class HlsConfig(
    val ladder: HlsLadder = HlsLadder.default(),
    val codec: VideoCodec = VideoCodec.H264,
    val segmentDurationSeconds: Int = 6,
    val maxParallelEncoders: Int = 1,
    val disableAudio: Boolean = false
)
```

- **Single codec per ladder.** Client runs again with a different codec if they want both H.264 and H.265.
- **`maxParallelEncoders`** defaults to 1 (sequential). Client can increase if target devices support multiple simultaneous hardware encoders.
- **`segmentDurationSeconds`** defaults to 6. HLS spec allows 2–10s. 6s balances startup latency and encoding efficiency.

### Encoding Ladder

```kotlin
data class Rendition(
    val resolution: Resolution,
    val bitrateKbps: Int
)

class HlsLadder(val renditions: List<Rendition>) {
    companion object {
        fun default() = HlsLadder(listOf(
            Rendition(Resolution.SD_360, 500),
            Rendition(Resolution.SD_540, 1200),
            Rendition(Resolution.HD_720, 2500),
            Rendition(Resolution.FHD_1080, 5000),
            Rendition(Resolution.UHD_4K, 15000),
        ))
    }
    fun drop(vararg labels: String): HlsLadder  // matches Resolution.label
    fun add(rendition: Rendition): HlsLadder
}
```

Default ladder for a 4K source:

| Rendition | Resolution  | Bitrate (H.264) | ~Segment size @6s |
|-----------|-------------|------------------|-------------------|
| 360p      | 640x360     | 500 kbps         | ~375 KB           |
| 540p      | 960x540     | 1200 kbps        | ~900 KB           |
| 720p      | 1280x720    | 2500 kbps        | ~1.9 MB           |
| 1080p     | 1920x1080   | 5000 kbps        | ~3.75 MB          |
| 4K        | 3840x2160   | 15000 kbps       | ~11 MB            |

Renditions above the source resolution are automatically skipped.

### Resolution Enum

Moved from the app module to the library. Extended with 360p.

```kotlin
enum class Resolution(val shortSide: Int, val label: String) {
    UHD_4K(2160, "4K"),
    FHD_1080(1080, "1080p"),
    HD_720(720, "720p"),
    SD_540(540, "540p"),
    SD_360(360, "360p")
}
```

The app module's existing `Resolution` enum is replaced by importing from the library.

### Listener (Streaming Callbacks)

```kotlin
interface HlsListener {
    fun onStart(renditionCount: Int)
    fun onRenditionStart(rendition: Rendition)
    fun onSegmentReady(rendition: Rendition, segment: HlsSegment)
    fun onRenditionComplete(rendition: Rendition, playlist: String)
    fun onComplete(masterPlaylist: String)
    fun onFailure(error: HlsError)
    fun onProgress(rendition: Rendition, percent: Float)
    fun onCancelled()
}

data class HlsSegment(
    val file: File,
    val index: Int,
    val durationSeconds: Double,
    val isInitSegment: Boolean
)

data class HlsError(
    val message: String,
    val failedRenditions: List<Rendition>,
    val completedRenditions: List<Rendition>
)
```

- **`onSegmentReady`**: The segment file is valid until the callback returns. The library deletes the temp file after the callback returns. Client must upload or copy the file before returning.
- **`onRenditionComplete`**: Delivers the media playlist (m3u8 content) as a string.
- **`onComplete`**: Delivers the master playlist as a string. Only includes successful renditions.
- **`onFailure`**: Fires only if all renditions fail. Reports which renditions succeeded vs failed.

### Playlist Rewriter

Public helper for remapping segment filenames to uploaded URLs.

```kotlin
object PlaylistRewriter {
    fun rewrite(
        playlist: String,
        urlMap: Map<String, String>
    ): String
}
```

The library emits playlists with predictable filenames (`init.mp4`, `segment_000.m4s`, etc.). The client uploads segments to content-addressed storage (e.g. Blossom), then uses `PlaylistRewriter` to replace filenames with actual URLs before uploading the playlists.

---

## Architecture

### Pipeline

```
Source URI
  |
  v
MediaExtractor (decode video track)
  |
  v
MediaCodec decoder -> OutputSurface (SurfaceTexture)
  |
  v
TextureRenderer (GL viewport sized to rendition resolution)
  |
  v
InputSurface -> MediaCodec encoder
  (KEY_I_FRAME_INTERVAL = segmentDurationSeconds)
  (KEY_BIT_RATE = rendition.bitrateKbps * 1000)
  |
  v
Encoder output buffers -> Mp4SegmentWriter
  |
  v
On keyframe boundary: flush segment -> onSegmentReady callback -> delete temp file
```

Renditions are processed sequentially (lowest resolution first), controlled by `maxParallelEncoders`. Lowest-first ordering means the client gets small, fast-to-upload segments first.

### fMP4 Muxer

Pure Kotlin fMP4 writer. No external dependencies.

**`Mp4SegmentWriter`** — high-level: takes encoded samples, writes fMP4 init and media segments.

```kotlin
internal class Mp4SegmentWriter(
    private val videoFormat: MediaFormat,
    private val audioFormat: MediaFormat?,
) {
    fun writeInitSegment(output: OutputStream)
    fun writeMediaSegment(
        videoSamples: List<EncodedSample>,
        audioSamples: List<EncodedSample>,
        sequenceNumber: Int,
        output: OutputStream
    )
}

internal data class EncodedSample(
    val data: ByteBuffer,
    val presentationTimeUs: Long,
    val flags: Int,
    val isAudio: Boolean
)
```

**`BoxWriter`** — low-level: writes ISO BMFF boxes.

```kotlin
internal class BoxWriter(private val output: OutputStream) {
    fun beginBox(type: String): BoxScope
    fun writeFullBox(type: String, version: Int, flags: Int): BoxScope
    fun writeUInt32(value: Long)
    fun writeUInt16(value: Int)
    fun writeBytes(data: ByteArray)
}
```

**Initialization segment** (`init.mp4`):
- `ftyp` (isom, iso5, iso6, msdh, msix)
- `moov` containing `mvhd`, `trak` (video), `trak` (audio if present), `mvex` with `trex` entries

**Media segment** (`segment_NNN.m4s`):
- `moof` containing `mfhd` (sequence number), `traf` per track (`tfhd`, `tfdt`, `trun`)
- `mdat` containing raw encoded samples

Codec-specific data (SPS/PPS for H.264, VPS/SPS/PPS for H.265) is extracted from `MediaCodec`'s `BUFFER_FLAG_CODEC_CONFIG` output and written into the `stsd` box. AAC config comes from the source audio track's `MediaFormat`.

No `sidx` box in v1 — optional for VOD.

### Segment Boundary Detection

In the encoder output loop, when a buffer has `BUFFER_FLAG_KEY_FRAME` and accumulated sample duration >= `segmentDurationSeconds`:

1. Flush accumulated samples to `Mp4SegmentWriter` as a media segment
2. Write to temp file
3. Call `listener.onSegmentReady()`
4. Delete temp file on callback return
5. Start accumulating next segment

### Keyframe Alignment

Critical for ABR switching. All renditions use:
- Same `segmentDurationSeconds` target
- `KEY_I_FRAME_INTERVAL` set to `segmentDurationSeconds` on every encoder
- Segment cuts at keyframe boundaries — since all encoders have the same I-frame interval, cuts align across renditions

### Scaling

The existing `InputSurface`/`OutputSurface`/`TextureRenderer` GL pipeline is reused. The encoder is configured with the rendition's target dimensions. `glViewport` is set to rendition dimensions before drawing. The encoder's input surface receives frames at the target resolution.

### Audio

AAC passthrough — no re-encoding. For each segment's time window, audio samples from `MediaExtractor` within that PTS range are muxed into the segment alongside video samples.

Audio is muxed into every rendition's segments (not a separate audio-only stream).

### Playlist Generation

**`PlaylistGenerator`** — internal, builds m3u8 strings.

```kotlin
internal class PlaylistGenerator(private val config: HlsConfig) {
    fun buildMediaPlaylist(
        rendition: Rendition,
        segments: List<SegmentInfo>
    ): String

    fun buildMasterPlaylist(
        completedRenditions: List<RenditionResult>
    ): String

    fun codecString(format: MediaFormat, codec: VideoCodec): String
}
```

**Master playlist** includes `BANDWIDTH`, `RESOLUTION`, and `CODECS` attributes per rendition. `CODECS` string is derived from encoder output format (e.g. `avc1.64001E` for H.264 High profile).

**Media playlist** uses `#EXT-X-VERSION:7` (required for fMP4), `#EXT-X-MAP` pointing to init segment, `#EXTINF` per segment with actual duration, `#EXT-X-ENDLIST` for VOD.

---

## Error Handling

**Skip-and-continue:** If a rendition fails, the library cleans up that rendition's resources and continues to the next. `onComplete` fires with a master playlist covering only successful renditions. `onFailure` fires only if all renditions fail.

```
// TODO: Consider client-controlled failure handling where
// onRenditionFailure callback returns Continue/Abort
```

**Resource cleanup:** Encoder, decoder, surfaces, and temp files are cleaned up in `finally` blocks per rendition and on cancellation.

### Cancellation

`HlsPreparer.cancel()`:
1. Sets cancellation flag checked in encode loop
2. Current in-progress segment is discarded
3. All resources cleaned up
4. `onCancelled()` fires
5. All temp files deleted

---

## Threading

```
HlsPreparer.start()
  -> CoroutineScope(Dispatchers.IO)     top-level job
    -> Dispatchers.Default               encode loop per rendition
```

| Callback              | Thread                          |
|-----------------------|---------------------------------|
| `onStart`             | Main                            |
| `onRenditionStart`    | Main                            |
| `onSegmentReady`      | Default (worker) — synchronous  |
| `onRenditionComplete` | Main                            |
| `onComplete`          | Main                            |
| `onFailure`           | Main                            |
| `onProgress`          | Default (worker)                |
| `onCancelled`         | Default (worker)                |

`onSegmentReady` stays on the worker thread because it's the hot path. The client performs blocking upload I/O and returns. This blocks the encode loop intentionally — bounded temp storage.

---

## Package Structure

```
lightcompressorlibrary/
  HlsPreparer.kt              (new - public)
  HlsConfig.kt                (new - public)
  HlsListener.kt              (new - public)
  Resolution.kt               (new - public, moved from app)
  PlaylistRewriter.kt          (new - public)
  VideoCompressor.kt           (existing - untouched)
  Configuration.kt             (existing - untouched)
  ...
  hls/
    HlsTranscoder.kt          (new - internal)
    PlaylistGenerator.kt      (new - internal)
    SegmentAccumulator.kt     (new - internal)
  muxer/
    Mp4SegmentWriter.kt       (new - internal)
    BoxWriter.kt              (new - internal)
  video/
    InputSurface.kt           (existing - reused)
    OutputSurface.kt          (existing - reused)
    TextureRenderer.kt        (existing - reused)
    ...
```

New code is isolated from existing compression. No modifications to `VideoCompressor`, `Compressor`, `Transcoder`, or the GL pipeline classes.

---

## Constraints

- **minSdk 21** — matching existing library. Bump to 24 only if a concrete blocker emerges.
- **No external dependencies** — fMP4 muxer is pure Kotlin.
- **Temp storage** — bounded by segment size since segments are deleted after callback. Worst case: one 4K segment (~11 MB) + encoder/decoder buffers.
- **Hardware codec limits** — `maxParallelEncoders` defaults to 1 to avoid device-specific limits. Client opts in to parallel encoding.

## Open Items for Implementation Plan

- Exact `glViewport` integration with existing `TextureRenderer`
- Codec config extraction (SPS/PPS/VPS) from `BUFFER_FLAG_CODEC_CONFIG` buffers
- AAC `esds` box construction from `MediaFormat` csd-0
- `CODECS` string derivation from encoder output format
- Unit test strategy for fMP4 box writing and playlist generation
- App module `Resolution` enum migration
