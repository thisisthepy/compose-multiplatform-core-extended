#!/usr/bin/env bash
# Runs the fork's buildSrc tests (extended/compose/buildsrc-tests): builds buildSrc through the
# fork's own build, then compiles and runs the tests against the jars it leaves in out/buildSrc.
set -euo pipefail
cd "$(dirname "$0")/../../.."
# Any task builds buildSrc first; `help` builds nothing else.
./gradlew help -q --max-workers="${GRADLE_WORKERS:-2}" -Pandroidx.enabled.kmp.target.platforms=-WINDOWS
./gradlew -p extended/compose/buildsrc-tests test --max-workers="${GRADLE_WORKERS:-2}" "$@"
