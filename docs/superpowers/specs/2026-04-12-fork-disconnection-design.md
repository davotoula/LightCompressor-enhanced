# Fork Disconnection & Package Rename

## Summary

Fully disconnect this project from the archived upstream (`AbedElazizShe/LightCompressor`). Rename the library package from `com.davotoula.lightcompressor` to `com.davotoula.lightcompressor`, rewrite the README, and remove all upstream ties.

This is a **breaking change** for existing consumers — all import statements must be updated. No API surface changes beyond the package rename.

## Decisions

| Decision | Choice |
|----------|--------|
| New library package | `com.davotoula.lightcompressor` |
| Module directory name | Keep `lightcompressor` (unchanged) |
| Root project name | `LightCompressor-enhanced` (match Maven artifact) |
| Maven coordinates | Keep `com.github.davotoula:LightCompressor-enhanced` (unchanged) |
| App applicationId | Keep `com.davotoula.lce` (unchanged) |
| README | Full rewrite |
| Attribution | One-liner "Originally forked from" with link |
| FUNDING.yml | Remove upstream author, keep `davotoula` only |
| Breaking change strategy | Clean break, bump major version at release time |
| Versioning | Out of scope — release later after manual verification |

## Changes

### 1. Package Rename

**From:** `com.davotoula.lightcompressor`
**To:** `com.davotoula.lightcompressor`

This affects:

- **16 main source files** — package declarations and directory structure
  - Move `lightcompressor/src/main/java/com/abedelazizshe/lightcompressorlibrary/` → `lightcompressor/src/main/java/com/davotoula/lightcompressor/`
  - Update `package` declaration in each file
  - Update intra-library imports

- **7 unit test files** — package declarations and directory structure
  - Move `lightcompressor/src/test/java/com/abedelazizshe/lightcompressorlibrary/` → `lightcompressor/src/test/java/com/davotoula/lightcompressor/`
  - Update `package` declarations and imports

- **1 instrumented test file**
  - Move `lightcompressor/src/androidTest/java/com/abedelazizshe/lightcompressorlibrary/` → `lightcompressor/src/androidTest/java/com/davotoula/lightcompressor/`

- **`lightcompressor/build.gradle`** — update `namespace` from `com.davotoula.lightcompressor` to `com.davotoula.lightcompressor`

- **App module** — update imports in `MainViewModel.kt` (and any other files referencing the library package)

### 2. Root Project Name

**File:** `settings.gradle`
**Change:** `rootProject.name` from `VideoCompressor` to `LightCompressor-enhanced`

### 3. README Rewrite

Full rewrite of `README.md`:

- Project description tailored to this fork's capabilities
- JitPack dependency instructions with current coordinates
- Usage examples with new `com.davotoula.lightcompressor` package
- Feature list reflecting current state (H.265, VideoResizer API, GIF-to-MP4, etc.)
- One-liner attribution: "Originally forked from [AbedElazizShe/LightCompressor](https://github.com/AbedElazizShe/LightCompressor)"
- Remove: original author email, contributor PR links to upstream, iOS library link, outdated screenshots/badges

### 4. GitHub Configuration

- **`.github/FUNDING.yml`** — remove `AbedElazizShe`, keep only `davotoula`

### 5. Git Remote

- Remove `upstream` remote (`git remote remove upstream`)

### 6. Documentation Updates

- **`CLAUDE.md`** — update all package references from `com.davotoula.lightcompressor` to `com.davotoula.lightcompressor`
- **`todo.md`** — mark fork detachment item as done
- **`docs/superpowers/plans/`** — update package names in existing plan files (3 files)
- **`docs/superpowers/specs/`** — update package names in existing spec files

### 7. Post-Change Verification

After all changes are complete:

1. `./gradlew assembleDebug` — confirm build passes
2. `./gradlew testDebugUnitTest` — confirm all tests pass
3. `./gradlew ktlintCheck` — confirm style checks pass
4. `./gradlew detekt` — confirm static analysis passes
5. Full grep scan for any remaining `abedelazizshe` references
6. Full grep scan for any remaining `lightcompressorlibrary` references (the old suffix)

## Out of Scope

- Version bump / release — done later after manual verification
- Changing Maven coordinates (already correct)
- Changing app applicationId (already correct)
- Any API changes beyond the package rename
