# Release Guide

This repository contains two separate components with independent release cycles:
1. **LightCompressor Library** - The core video compression library
2. **LightCompressor App** - Demo/sample Android application

## Prerequisites

### App Signing Setup (Required for App Releases)

Before releasing the app, you must configure signing keys for GitHub Actions. See [SIGNING_SETUP.md](SIGNING_SETUP.md) for detailed instructions on:
- Generating a keystore
- Converting it to base64
- Adding GitHub secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`

Without proper signing, the APK will show "Package invalid" errors on installation.

## Release Process

### Library Releases

The library is published to JitPack and can be used as a dependency in other Android projects.

**Steps to release the library:**

1. **Update the version** in `gradle.properties`:
   ```properties
   version=1.6.1
   ```

2. **Commit the version change**:
   ```bash
   git add gradle.properties
   git commit -m "Release commit: v1.6.1"
   ```

3. **Create and push a library tag**:
   ```bash
   git tag lib-v1.6.1
   git push origin lib-v1.6.1
   ```

4. **GitHub Actions will automatically**:
   - Build the library AAR
   - Create a GitHub release
   - Make it available on JitPack

5. **After release, update to next SNAPSHOT version**:
   ```properties
   version=1.6.2-SNAPSHOT
   ```
   ```bash
   git add gradle.properties
   git commit -m "Post release commit: v1.6.2-SNAPSHOT"
   git push
   ```

**Tag format**: `lib-v{major}.{minor}.{patch}` (e.g., `lib-v1.6.1`)

### App Releases

The demo app is distributed as an APK for testing the library functionality.

**Steps to release the app:**

1. **Update the app version** in `gradle.properties`:
   ```properties
   appVersionName=1.0.1
   appVersionCode=2
   ```

2. **Commit the version change**:
   ```bash
   git add gradle.properties
   git commit -m "Release app: v1.0.1"
   ```

3. **Create and push an app tag**:
   ```bash
   git tag app-v1.0.1
   git push origin app-v1.0.1
   ```

4. **GitHub Actions will automatically**:
   - Build the release APK and AAB
   - Create a GitHub release
   - Attach the APK and AAB files

**Tag format**: `app-v{major}.{minor}.{patch}` (e.g., `app-v1.0.1`)

**Note**: Remember to increment `appVersionCode` for each release (it must be monotonically increasing).

## Version Management

All versions are centralized in `gradle.properties`:

```properties
# Library version (used by JitPack)
version=1.6.1-SNAPSHOT

# App version
appVersionName=1.0.0
appVersionCode=1
```

## Automated Workflows

Two GitHub Actions workflows handle releases:

- `.github/workflows/library-release.yml` - Triggered by `lib-v*` tags
- `.github/workflows/app-release.yml` - Triggered by `app-v*` tags

## Versioning Convention

Both components follow [Semantic Versioning](https://semver.org/):
- **MAJOR** version for incompatible API changes
- **MINOR** version for backwards-compatible functionality additions
- **PATCH** version for backwards-compatible bug fixes

### Library Versioning Example
- Development: `1.6.1-SNAPSHOT`
- Release: `1.6.1`
- Next development: `1.6.2-SNAPSHOT`

### App Versioning Example
- versionName: `1.0.1` (user-facing version)
- versionCode: `2` (incremental build number)

## JitPack Usage

After releasing a library version, users can add it to their projects:

```gradle
// Root build.gradle
allprojects {
    repositories {
        maven { url 'https://jitpack.io' }
    }
}

// Module build.gradle
dependencies {
    implementation 'com.github.davotoula:LightCompressor-enhanced:1.6.1'
}
```

## Troubleshooting

**Q: JitPack build fails**
- Check that the version in `gradle.properties` matches the tag (without the `lib-v` prefix)
- Verify the tag was pushed to GitHub
- Check JitPack build logs at https://jitpack.io/com/github/davotoula/LightCompressor-enhanced/{version}/build.log

**Q: GitHub Actions workflow doesn't trigger**
- Verify the tag format matches the pattern (`lib-v*` or `app-v*`)
- Check GitHub Actions permissions in repository settings
- Review workflow logs in the Actions tab

**Q: Release artifacts are missing**
- Ensure the build succeeded in GitHub Actions
- Check that the workflow has `contents: write` permission
- Verify the paths in the workflow files match the actual build output locations
