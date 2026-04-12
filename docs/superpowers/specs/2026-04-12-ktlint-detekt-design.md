# Ktlint + Detekt Integration Design

**Date:** 2026-04-12
**Status:** Approved
**Scope:** Both modules (lightcompressor, app) — shared config, split app config only if needed

## Goals

- Enforce consistent Kotlin style via ktlint
- Catch potential bugs, complexity, and code smells via detekt
- Automate enforcement via git hooks (pre-commit, pre-push) and CI
- Use strict defaults that don't require extensive rewrites

## Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Scope | Both modules, shared config | Small codebase, consistency is cheap. Split app config only if too noisy |
| Ktlint plugin | jlleitschuh/ktlint-gradle | Most popular, multi-module support, auto-format tasks |
| Detekt plugin | Official io.gitlab.arturbosch.detekt | Only real option |
| Git hooks | Shell scripts + Gradle install task | No stale plugin dependency, fully transparent, auto-installs on build |
| CI enforcement | Fail build on violations | Config tuned to pass existing codebase, no reason for warnings-only |
| MagicNumber rule | Enabled (default) | Extract media constants into named values as part of this work |

## Plugin Setup

Versions added to `gradle/libs.versions.toml`. Plugins declared in root `build.gradle` with `apply false`, then applied in each module's `build.gradle`.

```groovy
// root build.gradle
plugins {
    id 'org.jlleitschuh.gradle.ktlint' version '<from-catalog>' apply false
    id 'io.gitlab.arturbosch.detekt' version '<from-catalog>' apply false
}
```

```groovy
// each module build.gradle
plugins {
    id 'org.jlleitschuh.gradle.ktlint'
    id 'io.gitlab.arturbosch.detekt'
}
```

## Ktlint Configuration

**`.editorconfig`** at project root:

```ini
root = true

[*.{kt,kts}]
indent_size = 4
indent_style = space
max_line_length = 120
insert_final_newline = true
```

**Gradle config** in each module:

```groovy
ktlint {
    version = libs.versions.ktlint.get()
    android = true
    outputToConsole = true
}
```

- `android = true` for Android-specific import rules
- Default ktlint rules used as-is
- One-off suppressions via `@Suppress("ktlint:...")` inline, not global disables

## Detekt Configuration

**`detekt.yml`** at project root. Uses `buildUponDefaultConfig = true` so the file contains only overrides.

### Relaxations from defaults

| Rule | Default | Our Value | Why |
|------|---------|-----------|-----|
| `LongMethod` | 60 lines | 100 lines | Codec/muxer methods are naturally longer |
| `ComplexMethod` | 15 | 20 | MediaCodec state machines have branching |
| `TooManyFunctions` | 11 | 20 | Utility classes and API surfaces |
| `LongParameterList` | 6 | 8 | Android API wrappers often need many params |

All other rules at defaults, including `MagicNumber` (enabled).

**Gradle config** in each module:

```groovy
detekt {
    config.setFrom(files("${rootProject.projectDir}/detekt.yml"))
    buildUponDefaultConfig = true
    allRules = false
}
```

Style rules overlapping with ktlint (indentation, naming) are handled by ktlint. Detekt focuses on complexity, bugs, exceptions, performance.

## Git Hooks

Shell scripts in `scripts/git-hooks/`, installed via a Gradle `Copy` task that runs automatically on build.

| Hook | Task | Rationale |
|------|------|-----------|
| `pre-commit` | `ktlintCheck` | Fast style check before every commit |
| `pre-push` | `detekt` | Deeper analysis before sharing code |

**`scripts/git-hooks/pre-commit`:**

```bash
#!/bin/sh
echo "Running ktlint..."
./gradlew ktlintCheck --daemon
```

**`scripts/git-hooks/pre-push`:**

```bash
#!/bin/sh
echo "Running detekt..."
./gradlew detekt --daemon
```

**Root `build.gradle` install task:**

```groovy
tasks.register('installGitHooks', Copy) {
    from("${rootProject.rootDir}/scripts/git-hooks")
    into("${rootProject.rootDir}/.git/hooks")
    fileMode = 0775
}

// Auto-install hooks when project is built
tasks.configureEach { task ->
    if (task.name == 'preBuild') {
        task.dependsOn('installGitHooks')
    }
}
```

Hooks are version-controlled in `scripts/git-hooks/`. Developers can bypass with `--no-verify` in emergencies but CI still catches violations.

## CI Integration

Updated CI command:

```bash
./gradlew assembleDebug testDebugUnitTest ktlintCheck detekt
```

No additional CI config needed. Both tools produce standard Gradle tasks.

## Implementation Order

1. Create feature branch (`feature/ktlint-detekt`)
2. Add plugin dependencies to version catalog and build files
3. Add `.editorconfig`
4. Run `ktlintCheck` — identify violations
5. Run `ktlintFormat` — auto-fix what it can, manual fix the rest
6. Add `detekt.yml` with relaxations
7. Run `detekt` — fix violations, extract magic numbers into named constants
8. Add git hook scripts and Gradle install task
9. Update CI command in workflow files
10. Verify end-to-end: clean build + tests + ktlintCheck + detekt all pass

Steps 4-5 and 7 are where code changes happen. The rest is config.

## App Module Fallback

If the app module produces too many detekt findings after initial tuning, create `app/detekt.yml` with further relaxed thresholds and update the app's `config.setFrom()` path. The library module always uses the strict shared config.
