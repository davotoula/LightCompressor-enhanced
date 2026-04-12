# Fork Disconnection & Package Rename Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fully disconnect from the archived upstream project and rename the library package from `com.davotoula.lightcompressor` to `com.davotoula.lightcompressor`.

**Architecture:** File moves for the directory tree rename (Java/Kotlin packages map to directory paths), then bulk `package`/`import` statement updates across all source, test, app, and doc files. Build config and metadata files updated individually.

**Tech Stack:** Kotlin, Gradle, Android SDK, Git

---

### Task 1: Move library main source files to new package directory

**Files:**
- Move directory: `lightcompressor/src/main/java/com/abedelazizshe/lightcompressorlibrary/` → `lightcompressor/src/main/java/com/davotoula/lightcompressor/`

The 16 source files and their subdirectories (`video/`, `compressor/`, `config/`, `utils/`, `data/`) all move together.

- [ ] **Step 1: Create the new directory structure**

```bash
mkdir -p lightcompressor/src/main/java/com/davotoula/lightcompressor
```

- [ ] **Step 2: Move all contents to the new location**

```bash
# Move all subdirectories and files
mv lightcompressor/src/main/java/com/abedelazizshe/lightcompressorlibrary/* \
   lightcompressor/src/main/java/com/davotoula/lightcompressor/
```

- [ ] **Step 3: Remove the empty old directory tree**

```bash
rm -rf lightcompressor/src/main/java/com/abedelazizshe
```

- [ ] **Step 4: Update package declarations in all 16 source files**

Replace `package com.davotoula.lightcompressor` with `package com.davotoula.lightcompressor` in every file. The subpackages stay the same (e.g., `.video`, `.compressor`, `.config`, `.utils`, `.data`).

Run:
```bash
find lightcompressor/src/main/java/com/davotoula/lightcompressor -name "*.kt" -exec \
  sed -i '' 's/com\.abedelazizshe\.lightcompressorlibrary/com.davotoula.lightcompressor/g' {} +
```

- [ ] **Step 5: Verify no old package references remain in main sources**

Run:
```bash
grep -r "abedelazizshe" lightcompressor/src/main/
```

Expected: no output.

- [ ] **Step 6: Commit**

```bash
git add lightcompressor/src/main/
git commit -m "refactor: move library sources to com.davotoula.lightcompressor package"
```

---

### Task 2: Move library test files to new package directory

**Files:**
- Move directory: `lightcompressor/src/test/java/com/abedelazizshe/lightcompressorlibrary/` → `lightcompressor/src/test/java/com/davotoula/lightcompressor/`
- Move directory: `lightcompressor/src/androidTest/java/com/abedelazizshe/lightcompressorlibrary/` → `lightcompressor/src/androidTest/java/com/davotoula/lightcompressor/`

7 unit test files + 1 instrumented test file.

- [ ] **Step 1: Create new test directory structures**

```bash
mkdir -p lightcompressor/src/test/java/com/davotoula/lightcompressor
mkdir -p lightcompressor/src/androidTest/java/com/davotoula/lightcompressor
```

- [ ] **Step 2: Move unit test files**

```bash
mv lightcompressor/src/test/java/com/abedelazizshe/lightcompressorlibrary/* \
   lightcompressor/src/test/java/com/davotoula/lightcompressor/
rm -rf lightcompressor/src/test/java/com/abedelazizshe
```

- [ ] **Step 3: Move instrumented test files**

```bash
mv lightcompressor/src/androidTest/java/com/abedelazizshe/lightcompressorlibrary/* \
   lightcompressor/src/androidTest/java/com/davotoula/lightcompressor/
rm -rf lightcompressor/src/androidTest/java/com/abedelazizshe
```

- [ ] **Step 4: Update package declarations and imports in all test files**

```bash
find lightcompressor/src/test/java/com/davotoula/lightcompressor -name "*.kt" -exec \
  sed -i '' 's/com\.abedelazizshe\.lightcompressorlibrary/com.davotoula.lightcompressor/g' {} +

find lightcompressor/src/androidTest/java/com/davotoula/lightcompressor -name "*.kt" -exec \
  sed -i '' 's/com\.abedelazizshe\.lightcompressorlibrary/com.davotoula.lightcompressor/g' {} +
```

- [ ] **Step 5: Verify no old package references remain in test sources**

Run:
```bash
grep -r "abedelazizshe" lightcompressor/src/test/ lightcompressor/src/androidTest/
```

Expected: no output.

- [ ] **Step 6: Commit**

```bash
git add lightcompressor/src/test/ lightcompressor/src/androidTest/
git commit -m "refactor: move library tests to com.davotoula.lightcompressor package"
```

---

### Task 3: Update build config and app module imports

**Files:**
- Modify: `lightcompressor/build.gradle:59` — namespace field
- Modify: `settings.gradle:10` — root project name
- Modify: `app/src/main/java/com/davotoula/lce/ui/main/MainViewModel.kt:12-19` — 8 import statements

- [ ] **Step 1: Update library namespace in build.gradle**

In `lightcompressor/build.gradle`, change line 59:

```groovy
// OLD:
namespace = 'com.davotoula.lightcompressor'
// NEW:
namespace = 'com.davotoula.lightcompressor'
```

- [ ] **Step 2: Update root project name in settings.gradle**

In `settings.gradle`, change line 10:

```groovy
// OLD:
rootProject.name='VideoCompressor'
// NEW:
rootProject.name='LightCompressor-enhanced'
```

- [ ] **Step 3: Update imports in MainViewModel.kt**

Replace all 8 imports in `app/src/main/java/com/davotoula/lce/ui/main/MainViewModel.kt` (lines 12-19):

```kotlin
// OLD:
import com.davotoula.lightcompressor.CompressionListener
import com.davotoula.lightcompressor.VideoCodec
import com.davotoula.lightcompressor.VideoCompressor
import com.davotoula.lightcompressor.config.Configuration
import com.davotoula.lightcompressor.config.SaveLocation
import com.davotoula.lightcompressor.config.SharedStorageConfiguration
import com.davotoula.lightcompressor.config.VideoResizer
import com.davotoula.lightcompressor.video.GifToMp4Converter

// NEW:
import com.davotoula.lightcompressor.CompressionListener
import com.davotoula.lightcompressor.VideoCodec
import com.davotoula.lightcompressor.VideoCompressor
import com.davotoula.lightcompressor.config.Configuration
import com.davotoula.lightcompressor.config.SaveLocation
import com.davotoula.lightcompressor.config.SharedStorageConfiguration
import com.davotoula.lightcompressor.config.VideoResizer
import com.davotoula.lightcompressor.video.GifToMp4Converter
```

- [ ] **Step 4: Check for any other app references to old package**

Run:
```bash
grep -r "abedelazizshe" app/
```

Expected: no output.

- [ ] **Step 5: Commit**

```bash
git add lightcompressor/build.gradle settings.gradle app/src/main/java/com/davotoula/lce/ui/main/MainViewModel.kt
git commit -m "refactor: update build config namespace and app imports for new package"
```

---

### Task 4: Build and test verification

- [ ] **Step 1: Build debug**

Run:
```bash
./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run all unit tests**

Run:
```bash
./gradlew testDebugUnitTest
```

Expected: All tests pass.

- [ ] **Step 3: Run ktlint**

Run:
```bash
./gradlew ktlintCheck
```

Expected: BUILD SUCCESSFUL (no violations).

- [ ] **Step 4: Run detekt**

Run:
```bash
./gradlew detekt
```

Expected: BUILD SUCCESSFUL (no violations).

---

### Task 5: Remove upstream git remote

- [ ] **Step 1: Remove the upstream remote**

Run:
```bash
git remote remove upstream
```

- [ ] **Step 2: Verify only origin remains**

Run:
```bash
git remote -v
```

Expected: only `origin` pointing to `davotoula/LightCompressor-enhanced`.

- [ ] **Step 3: Commit is not needed** — remote config is not tracked by git.

---

### Task 6: Update GitHub and project metadata

**Files:**
- Modify: `.github/FUNDING.yml:3` — remove upstream author
- Modify: `todo.md:8` — mark fork detachment done

- [ ] **Step 1: Update FUNDING.yml**

In `.github/FUNDING.yml`, change line 3:

```yaml
# OLD:
github: [AbedElazizShe, davotoula]
# NEW:
github: [davotoula]
```

- [ ] **Step 2: Mark todo item as done**

In `todo.md`, change line 8:

```markdown
# OLD:
- [ ] Detach from original fork (upstream AbedElazizShe/LightCompressor is archived/dead)
# NEW:
- [x] Detach from original fork (upstream AbedElazizShe/LightCompressor is archived/dead)
```

- [ ] **Step 3: Commit**

```bash
git add .github/FUNDING.yml todo.md
git commit -m "chore: update funding config and mark fork detachment done"
```

---

### Task 7: Rewrite README.md

**Files:**
- Rewrite: `README.md`

Replace the entire README with content focused on this fork's current capabilities.

- [ ] **Step 1: Write new README.md**

The new README should contain these sections in order:

1. **Badges** — keep existing JitPack and downloads badges (already point to fork)
2. **Project title and description** — LightCompressor-enhanced, what it does, that it's an actively maintained fork
3. **Installation** — JitPack setup with `settings.gradle` and dependency line
4. **Quick start** — minimal Kotlin usage example with `com.davotoula.lightcompressor` imports
5. **Features** — H.264/H.265, VideoResizer API, GIF-to-MP4, streamable output, audio control
6. **Configuration reference** — VideoQuality, bitrate options, codec, resizer, disableAudio, storage options
7. **Storage options** — SharedStorageConfiguration, AppSpecificStorageConfiguration, CacheStorageConfiguration, custom
8. **Sample app** — brief mention of the Compose sample app, download links (Obtainium, ZapStore)
9. **Compatibility** — min API 21
10. **Attribution** — one-liner: "Originally forked from [AbedElazizShe/LightCompressor](https://github.com/AbedElazizShe/LightCompressor)"
11. **License** — Apache 2.0

Key changes from old README:
- All code examples use `com.davotoula.lightcompressor` package
- Remove: original author email, contributor PR links, iOS library link, change logs section, outdated Kotlin version note, Flutter reference, "Common issues" whatsapp section, demo GIF
- Remove: duplicate "How to add to your project" section (consolidate into Installation)
- Keep: permission setup section (API < 29, 29-32, >= 33) — this is useful for consumers

```markdown
[![](https://jitpack.io/v/davotoula/LightCompressor-enhanced.svg)](https://jitpack.io/#davotoula/LightCompressor-enhanced)
![GitHub Downloads (all assets, all releases)](https://img.shields.io/github/downloads/davotoula/LightCompressor-enhanced/total)

# LightCompressor Enhanced

A powerful and easy-to-use Android video compression library using [MediaCodec](https://developer.android.com/reference/android/media/MediaCodec). Generates compressed MP4 video with configurable resolution, bitrate, and codec while maintaining good visual quality.

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

Add the dependency to your module's `build.gradle`:

```groovy
implementation 'com.github.davotoula:LightCompressor-enhanced:Tag'
```

You also need the Kotlin coroutines dependencies:

```groovy
implementation "org.jetbrains.kotlinx:kotlinx-coroutines-core:${Version.coroutines}"
implementation "org.jetbrains.kotlinx:kotlinx-coroutines-android:${Version.coroutines}"
```

## Quick Start

```kotlin
import com.davotoula.lightcompressor.VideoCompressor
import com.davotoula.lightcompressor.CompressionListener
import com.davotoula.lightcompressor.VideoCodec
import com.davotoula.lightcompressor.config.Configuration
import com.davotoula.lightcompressor.config.SharedStorageConfiguration
import com.davotoula.lightcompressor.config.SaveLocation
import com.davotoula.lightcompressor.config.VideoResizer

VideoCompressor.start(
    context = applicationContext,
    uris = uris,
    isStreamable = true,
    storageConfiguration = SharedStorageConfiguration(
        saveAt = SaveLocation.movies,
        subFolderName = "compressed"
    ),
    configureWith = Configuration(
        videoNames = listOf("video.mp4"),
        quality = VideoQuality.MEDIUM,
        isMinBitrateCheckEnabled = true,
        videoCodec = VideoCodec.H264
    ),
    listener = object : CompressionListener {
        override fun onStart(index: Int) {}
        override fun onProgress(index: Int, percent: Float) {}
        override fun onSuccess(index: Int, size: Long, path: String?) {}
        override fun onFailure(index: Int, failureMessage: String) {}
        override fun onCancelled(index: Int) {}
    }
)

// Cancel compression
VideoCompressor.cancel()
```

## Features

- **H.264 and H.265 (HEVC) encoding** — H.265 offers up to 50% better compression at the same quality level. Automatic device capability detection.
- **Flexible resolution control** via `VideoResizer`:
  - `VideoResizer.auto` — auto-resize based on original dimensions
  - `VideoResizer.scale(factor)` — scale by percentage
  - `VideoResizer.limitSize(maxSide)` — limit longest side
  - `VideoResizer.limitShortSide(maxShortSide)` — limit shortest side (orientation-aware, recommended)
  - `VideoResizer.matchSize(width, height)` — scale to match target dimensions
- **GIF to MP4 conversion** — convert animated GIFs to compressed MP4
- **Streamable output** — moov atom repositioning for progressive download
- **Granular bitrate control** — precise bps values (e.g., 1,500,000 bps) or simple Mbps values
- **Audio control** — compress with or without audio track
- **Native MediaMuxer** — uses Android's built-in muxer, no third-party dependencies

## Configuration

### VideoQuality

Controls bitrate relative to the original:

| Quality | Bitrate multiplier |
|---------|-------------------|
| `VERY_HIGH` | 0.6x |
| `HIGH` | 0.4x |
| `MEDIUM` | 0.3x |
| `LOW` | 0.2x |
| `VERY_LOW` | 0.1x |

### Bitrate options

- `videoBitrateInMbps` — custom bitrate in Mbps (integer)
- `videoBitrateInBps` — custom bitrate in bps (takes precedence over Mbps)
- `isMinBitrateCheckEnabled` — skip compression if bitrate is below 2 Mbps

### Codec selection

```kotlin
// H.265 with device check (recommended)
import com.davotoula.lightcompressor.utils.CompressorUtils

val codec = if (CompressorUtils.isHevcEncodingSupported()) {
    VideoCodec.H265
} else {
    VideoCodec.H264
}
```

## Storage Options

### SharedStorageConfiguration

Save to shared storage (Movies, Pictures, Downloads):

```kotlin
SharedStorageConfiguration(
    saveAt = SaveLocation.movies,
    subFolderName = "my-videos" // optional
)
```

### AppSpecificStorageConfiguration

Save to app-specific storage:

```kotlin
AppSpecificStorageConfiguration(
    subFolderName = "compressed" // optional
)
```

### CacheStorageConfiguration

Save to app cache directory:

```kotlin
CacheStorageConfiguration()
```

### Custom storage

Implement the `StorageConfiguration` interface for full control:

```kotlin
class MyStorageConfiguration : StorageConfiguration {
    override fun createFileToSave(
        context: Context,
        videoFile: File,
        fileName: String,
        shouldSave: Boolean
    ): File = // your logic
}
```

## Permissions

**API < 29:**

```xml
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"/>
<uses-permission
    android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="28"
    tools:ignore="ScopedStorage" />
```

**API 29–32:**

```xml
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
    android:maxSdkVersion="32"/>
```

**API >= 33 (Photo Picker recommended):**

```xml
<uses-permission android:name="android.permission.READ_MEDIA_VIDEO"/>
```

## Sample App

A Jetpack Compose sample app is included in the `app/` module demonstrating video selection, compression, and playback.

[![Obtanium](https://raw.githubusercontent.com/vitorpamplona/amethyst/main/docs/design/obtainium.png)](https://obtainium.imranr.dev/)[![ZapStore](https://raw.githubusercontent.com/vitorpamplona/amethyst/main/docs/design/zapstore.svg)](https://zapstore.dev/apps)

## Compatibility

Minimum Android SDK: API level 21

## Attribution

Originally forked from [AbedElazizShe/LightCompressor](https://github.com/AbedElazizShe/LightCompressor). Based on [Telegram](https://github.com/DrKLO/Telegram) for Android.

## License

[Apache License 2.0](LICENSE)
```

- [ ] **Step 2: Review the new README for accuracy**

Verify: all import paths use `com.davotoula.lightcompressor`, no upstream references remain except the attribution line.

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs: rewrite README for independent project with new package name"
```

---

### Task 8: Update CLAUDE.md

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update package references in CLAUDE.md**

Three changes:

1. Line 22 — test command example:
```bash
# OLD:
./gradlew :lightcompressor:testDebugUnitTest --tests "com.davotoula.lightcompressor.config.VideoResizerTest"
# NEW:
./gradlew :lightcompressor:testDebugUnitTest --tests "com.davotoula.lightcompressor.config.VideoResizerTest"
```

2. Line 42 — package declaration:
```markdown
# OLD:
Package: `com.davotoula.lightcompressor`
# NEW:
Package: `com.davotoula.lightcompressor`
```

3. Line 7 — update project overview to remove "Fork of" framing:
```markdown
# OLD:
Fork of LightCompressor — an Android video compression library. Two modules:
# NEW:
LightCompressor Enhanced — an Android video compression library. Two modules:
```

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: update CLAUDE.md package references for new package name"
```

---

### Task 9: Update docs/superpowers plan and spec files

**Files:**
- Modify: `docs/superpowers/plans/2026-04-12-defensive-error-handling.md`
- Modify: `docs/superpowers/plans/2026-03-28-deduplicate-transcoders.md`
- Modify: `docs/superpowers/specs/2026-04-12-fork-disconnection-design.md`

- [ ] **Step 1: Bulk replace old package name in all docs/superpowers files**

```bash
find docs/superpowers -name "*.md" -exec \
  sed -i '' 's/com\.abedelazizshe\.lightcompressorlibrary/com.davotoula.lightcompressor/g' {} +
```

- [ ] **Step 2: Verify no old references remain**

Run:
```bash
grep -r "abedelazizshe" docs/superpowers/
```

Expected: no output (the fork-disconnection spec may mention it in context — that's fine as it describes the migration itself).

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/
git commit -m "docs: update package references in superpowers plans and specs"
```

---

### Task 10: Final verification scan

- [ ] **Step 1: Full-repo grep for any remaining old references**

Run:
```bash
grep -r "abedelazizshe" --include="*.kt" --include="*.java" --include="*.gradle" --include="*.xml" --include="*.md" --include="*.yml" --include="*.yaml" --include="*.properties" .
```

Expected: zero matches in code/config files. The only acceptable matches are in the fork-disconnection design spec (which describes the migration) and the README attribution line.

- [ ] **Step 2: Grep for old package suffix**

Run:
```bash
grep -r "lightcompressorlibrary" --include="*.kt" --include="*.java" --include="*.gradle" --include="*.xml" --include="*.md" --include="*.yml" .
```

Expected: zero matches (the old suffix `lightcompressorlibrary` should not appear anywhere).

- [ ] **Step 3: Verify old directories are gone**

Run:
```bash
find . -path "*/abedelazizshe*" -not -path "./.git/*"
```

Expected: no output.

- [ ] **Step 4: Full build and test**

Run:
```bash
./gradlew assembleDebug testDebugUnitTest ktlintCheck detekt
```

Expected: BUILD SUCCESSFUL for all tasks.

- [ ] **Step 5: Fix any issues found and commit**

If any references remain or builds fail, fix and commit.
