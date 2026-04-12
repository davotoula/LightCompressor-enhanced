# Ktlint + Detekt Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add ktlint (style) and detekt (static analysis) to both modules with git hooks and CI enforcement.

**Architecture:** Gradle plugins applied per-module, shared config files at project root (`.editorconfig` for ktlint, `detekt.yml` for detekt). Git hooks as shell scripts in `scripts/git-hooks/`, auto-installed via Gradle Copy task. CI updated to run both checks.

**Tech Stack:** ktlint-gradle 14.2.0, detekt 1.23.8, Gradle (Groovy DSL)

**Spec:** `docs/superpowers/specs/2026-04-12-ktlint-detekt-design.md`

---

### Task 1: Create feature branch and add plugin dependencies

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `build.gradle`
- Modify: `lightcompressor/build.gradle`
- Modify: `app/build.gradle`

- [ ] **Step 1: Create feature branch**

```bash
git checkout -b feature/ktlint-detekt
```

- [ ] **Step 2: Add versions to catalog**

In `gradle/libs.versions.toml`, add to `[versions]`:

```toml
ktlint-gradle = "14.2.0"
detekt = "1.23.8"
```

- [ ] **Step 3: Add plugin declarations to root build.gradle**

In `build.gradle`, add a `plugins` block above the existing `buildscript` block:

```groovy
plugins {
    id 'org.jlleitschuh.gradle.ktlint' version "${libs.versions.ktlint.gradle.get()}" apply false
    id 'io.gitlab.arturbosch.detekt' version "${libs.versions.detekt.get()}" apply false
}
```

Note: The root `build.gradle` currently uses the legacy `buildscript` block. Gradle supports both in the same file. The `plugins` block must come first.

However, Groovy `build.gradle` cannot use `libs` inside the `plugins {}` block — versions must be hardcoded there or the plugins must be declared in `settings.gradle`. The simplest approach: declare the plugin versions directly.

```groovy
plugins {
    id 'org.jlleitschuh.gradle.ktlint' version '14.2.0' apply false
    id 'io.gitlab.arturbosch.detekt' version '1.23.8' apply false
}
```

- [ ] **Step 4: Apply plugins in lightcompressor/build.gradle**

Add to the existing `plugins` block:

```groovy
plugins {
    id 'com.android.library'
    id 'maven-publish'
    id 'io.github.simonhauck.release' version '1.5.1'
    id 'org.jlleitschuh.gradle.ktlint'
    id 'io.gitlab.arturbosch.detekt'
}
```

Add configuration blocks after the `android {}` block:

```groovy
ktlint {
    android = true
    outputToConsole = true
}

detekt {
    config.setFrom(files("${rootProject.projectDir}/detekt.yml"))
    buildUponDefaultConfig = true
    allRules = false
}
```

- [ ] **Step 5: Apply plugins in app/build.gradle**

Add to the existing `plugins` block:

```groovy
plugins {
    id 'com.android.application'
    id 'org.jetbrains.kotlin.plugin.compose'
    id 'com.google.gms.google-services'
    id 'com.google.firebase.crashlytics'
    id 'org.jlleitschuh.gradle.ktlint'
    id 'io.gitlab.arturbosch.detekt'
}
```

Add configuration blocks after the `android {}` block:

```groovy
ktlint {
    android = true
    outputToConsole = true
}

detekt {
    config.setFrom(files("${rootProject.projectDir}/detekt.yml"))
    buildUponDefaultConfig = true
    allRules = false
}
```

- [ ] **Step 6: Add .editorconfig at project root**

Create `.editorconfig`:

```ini
root = true

[*.{kt,kts}]
indent_size = 4
indent_style = space
max_line_length = 120
insert_final_newline = true
```

- [ ] **Step 7: Add detekt.yml at project root**

Create `detekt.yml`:

```yaml
complexity:
  LongMethod:
    threshold: 100
  ComplexMethod:
    threshold: 20
  TooManyFunctions:
    thresholdInFiles: 20
    thresholdInClasses: 20
    thresholdInInterfaces: 20
    thresholdInObjects: 20
    thresholdInEnums: 20
  LongParameterList:
    functionThreshold: 8
    constructorThreshold: 8
```

- [ ] **Step 8: Verify Gradle sync succeeds**

```bash
./gradlew help
```

Expected: BUILD SUCCESSFUL. This confirms the plugins resolve and the config files are found.

- [ ] **Step 9: Commit**

```bash
git add gradle/libs.versions.toml build.gradle lightcompressor/build.gradle app/build.gradle .editorconfig detekt.yml
git commit -m "build: add ktlint and detekt plugins with config"
```

---

### Task 2: Fix ktlint violations

**Files:**
- Modify: All `.kt` files with violations (identified by `ktlintCheck`)

- [ ] **Step 1: Run ktlintCheck to see all violations**

```bash
./gradlew ktlintCheck 2>&1 | tail -50
```

Review the output to understand the scope of violations.

- [ ] **Step 2: Run ktlintFormat to auto-fix**

```bash
./gradlew ktlintFormat
```

This auto-fixes most issues: trailing whitespace, import ordering, blank lines, spacing.

- [ ] **Step 3: Run ktlintCheck again to see remaining violations**

```bash
./gradlew ktlintCheck 2>&1 | tail -50
```

Expected: Fewer violations. Any remaining ones need manual fixes. Common manual fixes:
- Wildcard imports that need to be expanded
- Line length violations that need manual wrapping
- Naming convention issues

- [ ] **Step 4: Manually fix any remaining violations**

Fix each remaining violation. The specific files will depend on the ktlintCheck output from step 3.

- [ ] **Step 5: Verify ktlintCheck passes**

```bash
./gradlew ktlintCheck
```

Expected: BUILD SUCCESSFUL with no violations.

- [ ] **Step 6: Run tests to verify no regressions**

```bash
./gradlew testDebugUnitTest
```

Expected: All tests pass. Ktlint only changes formatting, not logic, so this should always pass.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "style: fix ktlint violations across both modules"
```

---

### Task 3: Fix detekt violations

**Files:**
- Modify: All `.kt` files with violations (identified by `detekt`)

- [ ] **Step 1: Run detekt to see all violations**

```bash
./gradlew detekt 2>&1 | tail -100
```

Review the output. Key violation types to expect:
- `MagicNumber` — numeric literals that should be named constants
- `ReturnCount` — functions with too many return statements
- `ThrowsCount` — functions with too many throw statements
- Other complexity/style findings

- [ ] **Step 2: Fix MagicNumber violations in the library module**

Extract magic numbers into named constants. For media-related constants (bitrates, dimensions, codec values), create companion object constants in the relevant class. For example:

```kotlin
// Before
if (bitrate < 2000000) { ... }

// After
companion object {
    private const val MIN_BITRATE = 2_000_000
}
if (bitrate < MIN_BITRATE) { ... }
```

Work through each file reported by detekt. Use Kotlin's underscore separator for large numbers (e.g., `2_000_000`).

- [ ] **Step 3: Fix MagicNumber violations in the app module**

Same approach as step 2 but for the app module files.

- [ ] **Step 4: Fix remaining detekt violations**

Address other violation types:
- `ReturnCount`: Restructure functions or suppress with `@Suppress("ReturnCount")` if the multiple returns genuinely improve readability
- `ThrowsCount`: Same approach
- Other findings: Fix or suppress with justification

- [ ] **Step 5: Run detekt to verify it passes**

```bash
./gradlew detekt
```

Expected: BUILD SUCCESSFUL with no violations.

- [ ] **Step 6: Decide if app needs separate config**

If the app module required many suppressions or was difficult to fix, create `app/detekt.yml` with relaxed thresholds and update `app/build.gradle`:

```groovy
detekt {
    config.setFrom(files("${rootProject.projectDir}/app/detekt.yml"))
    // ...
}
```

Skip this step if the shared config worked for both modules.

- [ ] **Step 7: Run full validation**

```bash
./gradlew assembleDebug testDebugUnitTest ktlintCheck detekt
```

Expected: BUILD SUCCESSFUL for all tasks.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "refactor: fix detekt violations and extract magic numbers"
```

---

### Task 4: Add git hooks and update CI

**Files:**
- Create: `scripts/git-hooks/pre-commit`
- Create: `scripts/git-hooks/pre-push`
- Modify: `build.gradle` (add installGitHooks task)
- Modify: `.github/workflows/gradle.yml`

- [ ] **Step 1: Create pre-commit hook script**

Create `scripts/git-hooks/pre-commit`:

```bash
#!/bin/sh
echo "Running ktlint..."
./gradlew ktlintCheck --daemon
```

- [ ] **Step 2: Create pre-push hook script**

Create `scripts/git-hooks/pre-push`:

```bash
#!/bin/sh
echo "Running detekt..."
./gradlew detekt --daemon
```

- [ ] **Step 3: Make scripts executable**

```bash
chmod +x scripts/git-hooks/pre-commit scripts/git-hooks/pre-push
```

- [ ] **Step 4: Add installGitHooks task to root build.gradle**

Add to `build.gradle` after the `buildscript` block:

```groovy
tasks.register('installGitHooks', Copy) {
    from("${rootProject.rootDir}/scripts/git-hooks")
    into("${rootProject.rootDir}/.git/hooks")
    fileMode = 0775
}

// Auto-install hooks on build
gradle.projectsEvaluated {
    tasks.matching { it.name == 'preBuild' }.configureEach {
        dependsOn(rootProject.tasks.named('installGitHooks'))
    }
}
```

- [ ] **Step 5: Test hook installation**

```bash
./gradlew installGitHooks
ls -la .git/hooks/pre-commit .git/hooks/pre-push
```

Expected: Both files exist and are executable (`-rwxrwxr-x` or similar).

- [ ] **Step 6: Test pre-commit hook**

```bash
git stash  # stash any changes to test with clean state
echo "// test" >> lightcompressor/src/main/java/com/abedelazizshe/lightcompressorlibrary/VideoCompressor.kt
git add -A
git commit -m "test hook" --dry-run
git checkout -- .
git stash pop
```

Note: `--dry-run` won't actually trigger the hook. To truly test, make a temporary commit and verify ktlint runs, then reset it. Alternatively, run the hook script directly:

```bash
./scripts/git-hooks/pre-commit
```

Expected: ktlint runs and passes.

- [ ] **Step 7: Update CI workflow**

In `.github/workflows/gradle.yml`, change line 38 from:

```yaml
      run: ./gradlew assembleDebug testDebugUnitTest
```

to:

```yaml
      run: ./gradlew assembleDebug testDebugUnitTest ktlintCheck detekt
```

- [ ] **Step 8: Run full end-to-end validation**

```bash
./gradlew assembleDebug testDebugUnitTest ktlintCheck detekt
```

Expected: BUILD SUCCESSFUL for all tasks.

- [ ] **Step 9: Commit**

```bash
git add scripts/git-hooks/pre-commit scripts/git-hooks/pre-push build.gradle .github/workflows/gradle.yml
git commit -m "ci: add git hooks and ktlint/detekt to CI pipeline"
```

---

### Task 5: Final verification and cleanup

- [ ] **Step 1: Clean build from scratch**

```bash
./gradlew clean assembleDebug testDebugUnitTest ktlintCheck detekt
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Verify git hooks are installed on build**

```bash
rm .git/hooks/pre-commit .git/hooks/pre-push
./gradlew assembleDebug
ls -la .git/hooks/pre-commit .git/hooks/pre-push
```

Expected: Hooks are reinstalled automatically.

- [ ] **Step 3: Review all changes**

```bash
git log --oneline feature/ktlint-detekt ^master
git diff master --stat
```

Review the full diff to ensure:
- No unintended logic changes (only formatting, constant extraction, config)
- All new files are tracked
- No secrets or build artifacts committed

- [ ] **Step 4: Update CLAUDE.md**

Add a new section to `CLAUDE.md` under `## Build Commands`:

```markdown
## Static Analysis

```bash
./gradlew ktlintCheck                     # Check Kotlin style (both modules)
./gradlew ktlintFormat                    # Auto-fix style violations
./gradlew detekt                          # Run static analysis (both modules)
./gradlew installGitHooks                 # Manually install git hooks
```

Git hooks auto-install on build. Pre-commit runs ktlint, pre-push runs detekt.
```

- [ ] **Step 5: Commit CLAUDE.md update**

```bash
git add CLAUDE.md
git commit -m "docs: add static analysis commands to CLAUDE.md"
```
