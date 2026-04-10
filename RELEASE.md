# Release Guide

This repository contains two separate components with independent release cycles:
1. **LightCompressor Library** - The core video compression library
2. **LightCompressor App** - Demo/sample Android application

## Prerequisites

### App Signing Setup (Required for App Releases)

Releasing the app requires the following encrypted secrets to be configured under
**GitHub → Settings → Secrets and variables → Actions**:

- `KEYSTORE_BASE64` — base64-encoded release keystore (`base64 -i release.keystore`)
- `KEYSTORE_PASSWORD` — keystore password
- `KEY_ALIAS` — signing key alias inside the keystore
- `KEY_PASSWORD` — signing key password

The `app-release.yml` workflow decodes `KEYSTORE_BASE64` to `release.keystore` on
the CI runner at build time. Without these secrets, the workflow falls back to a
throwaway debug keystore and the resulting APK will show "Package invalid" errors
on installation.

**Never commit** `release.keystore`, `.env`, or `local.properties`. They are listed
in `.gitignore` (`*.keystore`, `.env`, `local.properties`) and must stay local-only.

#### Local keystore location

Keep the release keystore **outside the project tree** to prevent accidental
`git add release.keystore` (an explicit `git add` on a named file bypasses
`.gitignore` entirely). Canonical location on the maintainer's dev machine:

```
~/.keys/lce/release.keystore      # chmod 600
~/.keys/lce/                      # chmod 700
```

For **local signed release builds** (e.g. testing the APK before a CI release),
add the following to `~/.gradle/gradle.properties` (user-global, never
project-local):

```properties
RELEASE_STORE_FILE=/Users/<you>/.keys/lce/release.keystore
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=...
RELEASE_KEY_PASSWORD=...
```

`app/build.gradle:30-35` picks these up via `project.hasProperty('RELEASE_STORE_FILE')`.
Without them, local release builds fall back to the Android debug keystore (fine
for verifying the build, useless for Play Store distribution — use CI for that).

**Disaster recovery**: store a base64 copy of the keystore + all four passwords
in a password manager (1Password, Bitwarden). This is the same format as the
`KEYSTORE_BASE64` GitHub secret and lets you restore if the local copy is lost:

```bash
base64 -i ~/.keys/lce/release.keystore | pbcopy   # paste into password manager
```

## Release Process

### Library Releases

The library is published to JitPack and can be used as a dependency in other Android projects.

**Steps to release the library:**

1. **Update the version** in `lightcompressor/gradle.properties`:
   ```properties
   version=1.6.1
   ```

2. **Commit the version change**:
   ```bash
   git add lightcompressor/gradle.properties
   git commit -m "Release commit: v1.6.1"
   ```

3. **Create and push a library tag**:
   ```bash
   git tag v1.6.1
   git push origin v1.6.1
   ```

4. **GitHub Actions will automatically**:
   - Build the library AAR
   - Create a GitHub release
   - Make it available on JitPack

5. **After release, update to next SNAPSHOT version** in `lightcompressor/gradle.properties`:
   ```properties
   version=1.6.2-SNAPSHOT
   ```
   ```bash
   git add lightcompressor/gradle.properties
   git commit -m "Post release commit: v1.6.2-SNAPSHOT"
   git push
   ```

**Tag format**: `v{major}.{minor}.{patch}` (e.g., `v1.6.1`)

#### Release notes

`library-release.yml` uses `softprops/action-gh-release@v2` with
`generate_release_notes: true`, so the GitHub release body is auto-populated with
a commit list since the previous tag, appended after the static Installation
snippet. No manual step is needed for a basic release.

For a curated changelog (recommended for user-facing releases), edit the release
body after the workflow finishes:

```bash
gh release edit v1.6.1 --repo davotoula/LightCompressor-enhanced \
  --notes-file /tmp/release-notes.md
```

Or view the auto-generated notes first, then edit in place:

```bash
gh release view v1.6.1 --repo davotoula/LightCompressor-enhanced
gh release edit v1.6.1 --repo davotoula/LightCompressor-enhanced --notes "..."
```

#### Verifying the release

After the workflow completes, confirm JitPack serves the coordinate cleanly:

```bash
VERSION=1.6.1
curl -sS -o /dev/null -w "POM %{http_code}\n" \
  "https://jitpack.io/com/github/davotoula/LightCompressor-enhanced/${VERSION}/LightCompressor-enhanced-${VERSION}.pom"
curl -sS -o /dev/null -w "AAR %{http_code} %{size_download} bytes\n" \
  "https://jitpack.io/com/github/davotoula/LightCompressor-enhanced/${VERSION}/LightCompressor-enhanced-${VERSION}.aar"
```

Both should return HTTP 200. The first request triggers JitPack's build from the
tag (takes 1–3 minutes); subsequent requests are cached. If either returns 404,
check https://jitpack.io/com/github/davotoula/LightCompressor-enhanced/${VERSION}/build.log

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

Library version lives in `lightcompressor/gradle.properties`:

```properties
# Library version (used by JitPack)
version=1.6.1-SNAPSHOT
```

App version lives in root `gradle.properties`:

```properties
appVersionName=1.0.0
appVersionCode=1
```

## Automated Workflows

Two GitHub Actions workflows handle releases:

- `.github/workflows/library-release.yml` - Triggered by `v*` tags
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
- Check that the version in `lightcompressor/gradle.properties` matches the tag (without the `v` prefix)
- Verify the tag was pushed to GitHub
- Check JitPack build logs at https://jitpack.io/com/github/davotoula/LightCompressor-enhanced/{version}/build.log

**Q: GitHub Actions workflow doesn't trigger**
- Verify the tag format matches the pattern (`v*` for library, `app-v*` for app)
- Check GitHub Actions permissions in repository settings
- Review workflow logs in the Actions tab

**Q: Release artifacts are missing**
- Ensure the build succeeded in GitHub Actions
- Check that the workflow has `contents: write` permission
- Verify the paths in the workflow files match the actual build output locations
