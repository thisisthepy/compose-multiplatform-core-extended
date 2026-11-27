#!/usr/bin/env bash
# Checks that every Gradle call in compose-tests.yml's "Compile web test executables" steps goes
# through retry-network.sh. Those steps resolve the build's plugins and dependencies first; a
# lookup the network dropped there ended a Web Chrome cell in fork-train-19 (45924331837):
# "could not resolve plugin artifact org.jetbrains.kotlin.plugin.serialization", no HTTP error,
# while the same lookup passed in the other web cells. retry-network.sh runs such a failure again.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
workflow="${1:-$(cd "$here/../../.." && pwd)/.github/workflows/compose-tests.yml}"

echo "web compile retry"
# The run blocks of those steps: from the step's name to the next step.
calls="$(awk '
    /^ *- name: / { inside = ($0 ~ /- name: Compile web test executables *$/); next }
    inside && /\.\/gradlew/ { print FILENAME ":" FNR ": " $0 }
' "$workflow")"
[[ -n "$calls" ]] || { echo "  FAIL: no Gradle call in a Compile web test executables step of $workflow" >&2; exit 1; }
bare="$(grep -v 'retry-network\.sh' <<< "$calls" || true)"
if [[ -n "$bare" ]]; then
    echo "  FAIL: Gradle called without retry-network.sh:" >&2
    echo "$bare" >&2
    exit 1
fi
echo "ok: $(wc -l <<< "$calls" | tr -d ' ') Gradle calls go through retry-network.sh"
