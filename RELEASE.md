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
the canonical setup uses an env file at `~/.keys/lce/release.env` (mirrors the
`zapstore.env` pattern and matches the CI signing path in `app/build.gradle:28-33`):

```
KEYSTORE_FILE=/Users/<you>/.keys/lce/release.keystore
KEYSTORE_PASSWORD=...
KEY_ALIAS=...
KEY_PASSWORD=...
```

Source it into a single gradle invocation — secrets never land in
`gradle.properties` or shell history:

```bash
set -a && source ~/.keys/lce/release.env && set +a && ./gradlew :app:assembleRelease
```

Alternatively — the older pattern — put `RELEASE_STORE_FILE` / `RELEASE_STORE_PASSWORD` /
`RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD` properties in `~/.gradle/gradle.properties`
(user-global). `app/build.gradle:34-39` picks these up via `project.hasProperty('RELEASE_STORE_FILE')`.
Prefer the env-file approach for new setups.

Without either, local release builds fall back to the Android debug keystore
(fine for verifying `BuildConfig` values, useless for Play Store distribution —
use CI for that).

**Verify the APK is signed with the release keystore** (not the Android debug
cert) before trusting it:

```bash
/Users/<you>/Library/Android/sdk/build-tools/<latest>/apksigner verify --print-certs \
  app/build/outputs/apk/release/lce-app-release.apk | head -5
```

If `Signer #1 certificate DN` contains `CN=Android Debug`, the signing env
didn't pick up — re-check `release.env` was sourced into the same shell that
ran gradle. `$ANDROID_HOME` is typically unset on macOS, so use the absolute
path above.

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
   ```

   **Back-to-back releases:** if you are immediately cutting an app release that
   needs to bundle this exact library version, **skip step 5** and leave
   `lightcompressor/gradle.properties` at the released version (e.g. `1.9.0`)
   through the app release. The SNAPSHOT bump then happens once, in a single
   post-release commit after the app tag. This avoids the temp-pin-then-revert
   dance in the "Bundling a tagged library version in the app release" section
   below. See commit `778e483` (`app-v1.4.0`) for the 3-commit back-to-back
   pattern: `Release commit: v1.9.0` → `Release commit: app-v1.4.0` → `Post
   release commit: app-v1.4.1-SNAPSHOT`.

   ```bash
   # (standalone library release only — for back-to-back, skip this block)
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

#### Bundling a tagged library version in the app release

The app UI displays the library version read from `lightcompressor/gradle.properties`
(see `app/build.gradle:21`). App releases going to Play Store / Zapstore must
ship a **tagged** library version, never a `-SNAPSHOT` suffix.

`.github/workflows/app-release.yml` enforces this with a guard step that fails
the workflow if `lightcompressor/gradle.properties` contains `-SNAPSHOT` at the
tagged commit.

Recommended flow — two patterns depending on whether the library release
is standalone or followed immediately by an app release:

**Pattern A — back-to-back (library + app in one session, preferred).** See
`app-v1.4.0` for the canonical example.

1. Cut the library release (e.g. `v1.9.0`) **and skip the post-release
   `-SNAPSHOT` bump** — `lightcompressor/gradle.properties` stays at `1.9.0`.
2. Create a "Release commit: app-v1.x.y" that only touches root
   `gradle.properties` (new `appVersionName` + `appVersionCode`) and
   `CHANGELOG.md`. Library file is untouched because it's already at the
   released version.
3. Run the mandatory on-device label check (see below).
4. Tag and push: `git tag app-v1.x.y && git push origin master app-v1.x.y`
5. After the workflow succeeds, create a single "Post release commit" that
   bumps **both** library (`1.9.1-SNAPSHOT`) and app (`1.4.1-SNAPSHOT`,
   `versionCode 141`) in one commit.

**Pattern B — delayed app release (library already on `-SNAPSHOT`).** See
`app-v1.3.2` for the canonical example.

1. The library was released earlier; master is at `1.8.4-SNAPSHOT`.
2. In the "Release commit: app-v1.x.y", temporarily pin
   `lightcompressor/gradle.properties` back to the released library version
   (e.g. `1.8.3`). The app ships with this pinned version.
3. Run the mandatory on-device label check.
4. Tag and push.
5. Post-release commit bumps the library back to `-SNAPSHOT` and the app to
   the next `-SNAPSHOT`.

**Mandatory on-device label check (both patterns):**

```bash
# Build signed release APK (see "App Signing Setup" for release.env sourcing)
set -a && source ~/.keys/lce/release.env && set +a
./gradlew :app:assembleRelease

# Verify keystore certificate (not Android debug)
/Users/<you>/Library/Android/sdk/build-tools/<latest>/apksigner verify --print-certs \
  app/build/outputs/apk/release/lce-app-release.apk | head -5

# Install on a connected device
adb install -r app/build/outputs/apk/release/lce-app-release.apk
```

Open the app on the device, scroll to the bottom of the main screen. The
version label at `MainScreen.kt:337` **must** read exactly:

```
App v<new-version> • Lib v<released-library-version>
```

with **no `-SNAPSHOT` suffix on either side**. If it does, abort the release
and fix the gradle.properties files — do not push the tag. The CI guard in
`app-release.yml` catches this as a second line of defence, but the on-device
check is faster to diagnose and verifies the rendered label, not just the
file contents.

### Publishing to Zapstore

After the GitHub release for `app-v*` is live, publish to Zapstore with the
`zsp` CLI (Go rewrite of the original Dart `zapstore` client).

**Install zsp**

```bash
go install github.com/zapstore/zsp@latest
```

Binary lands in `$HOME/go/bin/zsp`. Add that directory to `PATH` in `~/.zshrc`
if it isn't already:

```bash
echo 'export PATH="$HOME/go/bin:$PATH"' >> ~/.zshrc
```

**One-time setup — sign with a local nsec file**

Zapstore signing key lives alongside the release keystore, outside the repo:

```
~/.keys/lce/zapstore.env       # chmod 600
```

File contents (one line):

```
SIGN_WITH=nsec1...your_nsec_here
```

Other accepted values: 64-char hex, `NIP07` (browser extension),
`bunker://...` (NIP-46 remote signer).

**Disaster recovery**: store a copy of the nsec in your password manager
alongside the keystore secrets.

**Validate config before publishing**

```bash
zsp publish --check zapstore.yaml
```

A success response looks like `{"package_id":"com.davotoula.lce"}`. This
verifies that `zapstore.yaml` parses, the GitHub release is reachable, and an
arm64-v8a APK matches the `match:` regex — all without signing or uploading.

**Publishing a release**

From the repo root, with a clean working tree on the tagged commit:

```bash
set -a
source ~/.keys/lce/zapstore.env
set +a
zsp publish --skip-preview zapstore.yaml
```

`--skip-preview` avoids the browser preview prompt that would otherwise
block a non-interactive shell. `set -a` auto-exports every assignment
sourced from `zapstore.env` so `zsp` (a child process) inherits `SIGN_WITH`.

**First-time NIP-C1 certificate linking**

On your first publish, `zsp` prompts for the APK signing keystore to link
the certificate to your Nostr identity (NIP-C1). Supply the **absolute**
path — `~` is not expanded by the prompt:

```
Path to your keystore (.jks / .keystore): /Users/<you>/.keys/lce/release.keystore
Key alias (leave blank to use first alias): <Enter>
Keystore password: <your keystore password>
```

To skip and link later: pass `--skip-certificate-linking`, then run
`zsp identity --link-key /Users/<you>/.keys/lce/release.keystore` separately.

**What gets published**

`zsp` reads `zapstore.yaml` for app metadata (summary, description, tags)
and `CHANGELOG.md` for release notes. It pulls the `app-v*` GitHub release
assets matching `match:` (`.*\.apk$`), uploads them to
`https://cdn.zapstore.dev` via Blossom, and broadcasts kind 32267 (app)
and kind 30063 (release) events to `wss://relay.zapstore.dev`.

After publishing, users on Zapstore will see the update on their next sync.

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
