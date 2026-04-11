#!/bin/bash
#
# Claude Code on the web — SessionStart hook
#
# Warms the Gradle caches so that ./gradlew assembleDebug / testDebugUnitTest
# run quickly (and offline where possible) during the interactive session.
#
# Runs asynchronously: returns immediately, does the heavy lifting in the
# background while the session is starting up. Session commands that hit
# Gradle before this completes may still race — see README in this folder
# if you prefer synchronous mode.
#
# Idempotent: safe to re-run. Failures from individual Gradle invocations do
# not abort the hook — the session will still start, just with a cold cache.
set -uo pipefail

# Emit the async control message on stdout FIRST, before any other output.
# The harness reads this line to decide whether to run the hook in the
# background. 10-minute timeout covers a cold AGP + Kotlin + Compose download.
echo '{"async": true, "asyncTimeout": 600000}'

# Only run in the Claude Code on the web environment. Local developer shells
# already have populated caches and don't need this.
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(pwd)}"
cd "$PROJECT_DIR"

log() { echo "[session-start] $*"; }

log "warming Gradle caches for LightCompressor"
log "project dir: $PROJECT_DIR"

# -----------------------------------------------------------------------------
# 1. Ensure the Gradle distribution itself is downloaded.
#    services.gradle.org is in the standard allowed_hosts, so this always works.
#    ./gradlew --version does not trigger project configuration, so it won't
#    fail even if Google Maven is unreachable.
# -----------------------------------------------------------------------------
log "downloading Gradle wrapper distribution if needed"
./gradlew --version --no-daemon >/tmp/session-start-gradle-version.log 2>&1 || {
  log "WARNING: ./gradlew --version failed — wrapper download or JDK issue"
  log "see /tmp/session-start-gradle-version.log"
}

# -----------------------------------------------------------------------------
# 2. Preflight: check whether Google Maven (dl.google.com) is reachable.
#    The Android Gradle Plugin, Firebase Gradle plugins, and the Compose
#    Compiler plugin are hosted only on dl.google.com / maven.google.com.
#    If that host is not in the Claude Code web egress allowlist, Gradle
#    configuration phase will always fail — no amount of cache warming helps.
# -----------------------------------------------------------------------------
AGP_POM_URL="https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/9.1.0/gradle-9.1.0.pom"
if curl -sfI --max-time 10 "$AGP_POM_URL" >/dev/null 2>&1; then
  log "dl.google.com reachable — warming plugin + dependency caches"

  # help forces the root buildscript classpath to resolve (AGP, Firebase,
  # Compose, Crashlytics, Google Services). Once this finishes, every
  # subsequent gradle invocation in the session configures against the
  # local cache instead of the network.
  log "running ./gradlew help to resolve plugin classpath"
  ./gradlew --no-daemon help >/tmp/session-start-gradle-help.log 2>&1 || {
    log "WARNING: ./gradlew help failed — see /tmp/session-start-gradle-help.log"
  }

  # Resolve the full library dependency graph (Kotlin stdlib, coroutines,
  # AndroidX annotation, JUnit, MockK, etc.) so the test task runs offline.
  log "running ./gradlew :lightcompressor:dependencies to prefetch library deps"
  ./gradlew --no-daemon :lightcompressor:dependencies --configuration testDebugRuntimeClasspath \
    >/tmp/session-start-gradle-deps.log 2>&1 || {
    log "WARNING: dependency resolution hit errors — see /tmp/session-start-gradle-deps.log"
  }
else
  log "WARNING: dl.google.com is NOT reachable from this session's egress"
  log "WARNING: The Android Gradle Plugin (9.1.0), Firebase Crashlytics Gradle"
  log "WARNING: plugin, google-services plugin, and Compose Compiler plugin are"
  log "WARNING: hosted only at dl.google.com / maven.google.com, neither of"
  log "WARNING: which is in this container's allowed_hosts list."
  log "WARNING:"
  log "WARNING: ./gradlew assembleDebug and ./gradlew testDebugUnitTest will"
  log "WARNING: fail during Gradle configuration until those hosts are added"
  log "WARNING: to the Claude Code web egress allowlist for this repo."
  log "WARNING:"
  log "WARNING: Ask your Claude Code admin to allow:"
  log "WARNING:   dl.google.com"
  log "WARNING:   maven.google.com"
fi

log "done"
