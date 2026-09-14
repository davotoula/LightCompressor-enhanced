## [app-v2.0.0] - 2026-09-14

### Added
- "Prepare HLS + Upload" smoke-test affordance with per-rendition upload progress
- Per-app language support; hardcoded strings extracted and translations completed
- Analytics events for the HLS screen (open, prepare start, result)
- Bundles LightCompressor library **2.2.2** (unified H.264/H.265 Transcoder, HLS preparation with optional single-file-per-rendition output, `HlsUploadHelper` orchestrator)

### Changed
- compileSdk 37 (targetSdk stays 36)
- Dependencies updated to latest stable: Kotlin 2.3.20, AGP 9.1, Compose BOM 2026.09.00, Media3 1.11.1, Gradle 9.7.1
- CI: Dependabot for Gradle and GitHub Actions; CodeQL scanning of Kotlin sources and workflow files

### Fixed
- 1px white border on GIF-to-MP4 output for odd-sized GIFs (library)

## [app-v1.4.0] - 2026-04-11

### Added
- Accept shared GIFs from other apps (`image/gif` share intent-filters)
- GIFs in the in-app media picker (accepts `image/gif` alongside `video/*`)
- Route GIFs through new `GifToMp4Converter` (library) to produce compressed MP4 output
- Debug build installs side-by-side with release (dev `applicationIdSuffix`)
- Bundles LightCompressor library **v1.9.0** (new `GifToMp4Converter`)

### Fixed
- GL/EGL resource leaks in `GifToMp4Converter`
- Sonar: consume `File.delete()` result and flatten nested `if` in `MainViewModel` / `Transcoder`

## [app-v1.3.2] - 2026-04-10

### Added
- Bundles LightCompressor library v1.8.3 (unified Transcoder for H.264/H.265)

KNOWN ISSUE: some (social media) videos lose sound on compression. Investigating.

### Changed
- Resizing is now based on short edge
- Release APK/AAB renamed to `lce-app-release.apk` / `lce-app-release.aab`
- Updated AGP
- Updated dependencies to latest stable

### Fixed
- Resize bug for portrait videos
- UI library version label now shows tagged version instead of `-SNAPSHOT` suffix

## [app-v1.3.0] - 2025-12-29

### Added
- Switchable Dark / Light mode (persisted)
- Collapsable settings to have more room for compressed videos results
- Auto update bitrate when different resolution / codec is selected
- Share to other android apps
- Thumbnail extraction
- Persist video settings (resolution, video codec, streamable, bitrate)
- Add firebase logging for compressed videos without sound
- Translate to swedish, czech, Portuguese and german

KNOWN ISSUE: some (social media) videos lose sound on compression. Investigating.

### Changed
- Compressed movies saved to Movies/lce-compressed
- Migrate from XML to Compose

### Fixed
- Check for untranslated strings

## [app-v1.2.2] - 2025-11-27

### Fixed
- APK signing

## [app-v1.2.1] - 2025-11-27

### Added
- Privacy policy for Google Play Store
- Firebase Analytics integration
- UI numbers to guide user through the compression process
- Android Photo Picker support (API 33+)

### Changed
- Updated targetSdkVersion to 36
- Upgraded Firebase BOM to latest version
- Enhanced top bar UI

### Fixed
- Top bar display issues
- UI button highlighting behavior

[Unreleased]: https://github.com/davotoula/LightCompressor-enhanced/compare/app-v1.4.0...HEAD
[app-v1.4.0]: https://github.com/davotoula/LightCompressor-enhanced/releases/tag/app-v1.4.0
[app-v1.3.2]: https://github.com/davotoula/LightCompressor-enhanced/releases/tag/app-v1.3.2
[app-v1.3.0]: https://github.com/davotoula/LightCompressor-enhanced/releases/tag/app-v1.3.0
[app-v1.2.2]: https://github.com/davotoula/LightCompressor-enhanced/releases/tag/app-v1.2.2
[app-v1.2.1]: https://github.com/davotoula/LightCompressor-enhanced/releases/tag/app-v1.2.1