# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [app-v1.3.2] - 2026-04-10

### Added
- Updated AGP
- Updated dependencies to latest stable
- Bundles LightCompressor library v1.8.3 (unified Transcoder for H.264/H.265)

KNOWN ISSUE: some (social media) videos lose sound on compression. Investigating.

### Changed
- Resizing is now based on short edge
- Release APK/AAB renamed to `lce-app-release.apk` / `lce-app-release.aab`

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

[Unreleased]: https://github.com/davotoula/LightCompressor/compare/v1.2.1...HEAD
[1.2.1]: https://github.com/davotoula/LightCompressor/releases/tag/v1.2.1