#!/usr/bin/env bash
# Checks that every workflow running this fork's Gradle build names the Maven mirror
# (COMPOSE_MAVEN_MIRROR, which buildSrc/repos.gradle asks before Maven Central), and every one
# running the Kotlin toolchain (Amper: ./kotlin, kotlin.bat) points its Maven Central at it
# (KOTLIN_DEFAULT_MAVEN_CENTRAL_URL, which Amper reads in place of repo1.maven.org), in its own
# top-level env. A workflow called with workflow_call does not inherit its caller's env, so each
# one says it itself. Nothing is built.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
workflows="${1:-$(cd "$here/../../.." && pwd)/.github/workflows}"
failures=0
fail() { echo "  FAIL: $1" >&2; failures=$((failures + 1)); }

# What runs the fork's Gradle build: the wrapper, the scripts that call it, the action that
# publishes the fork's Compose with it.
runs_gradle='gradlew|publish-local\.sh|actions/fork-compose|buildsrc-tests\.sh|retry-network\.sh'

# The top-level env block: from "env:" at column 0 to the next line at column 0.
top_level_env() {
    awk '/^env:/ { inside = 1; next } inside && /^[^ #]/ { inside = 0 } inside { print }' "$1"
}

echo "ci mirror"
checked=0
for workflow in "$workflows"/*.yml; do
    grep -qE "$runs_gradle" "$workflow" || continue
    checked=$((checked + 1))
    if ! top_level_env "$workflow" | grep -qE '^  COMPOSE_MAVEN_MIRROR: *[^ ]'; then
        fail "$(basename "$workflow") runs the Gradle build and does not set COMPOSE_MAVEN_MIRROR in its top-level env"
    fi
done
[[ $checked -gt 0 ]] || fail "no workflow under $workflows runs the Gradle build"

runs_amper='\./kotlin\b|kotlin\.bat'
amper=0
for workflow in "$workflows"/*.yml; do
    grep -qE "$runs_amper" "$workflow" || continue
    amper=$((amper + 1))
    if ! top_level_env "$workflow" | grep -qE '^  KOTLIN_DEFAULT_MAVEN_CENTRAL_URL: *[^ ]'; then
        fail "$(basename "$workflow") runs the Kotlin toolchain and does not set KOTLIN_DEFAULT_MAVEN_CENTRAL_URL in its top-level env"
    fi
done
[[ $amper -gt 0 ]] || fail "no workflow under $workflows runs the Kotlin toolchain"

if [[ $failures -gt 0 ]]; then
    echo "$failures failed" >&2
    exit 1
fi
echo "ok: $checked Gradle and $amper Kotlin toolchain workflows"
