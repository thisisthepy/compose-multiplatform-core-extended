#!/usr/bin/env bash
# Tests retry-network.sh with a made-up build that fails once with a given message, with no build.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
compose="$(cd "$here/.." && pwd)"
work="$(mktemp -d "${RUNNER_TEMP:-$here}/retry-network.XXXXXX")"
trap 'rm -rf "$work"' EXIT
failures=0
fail() { echo "  FAIL: $1" >&2; failures=$((failures + 1)); }

# A build that prints the message and fails on its first run and passes on the next. It counts
# its runs in a file, so the test can tell how many times retry-network.sh ran it.
cat > "$work/build.sh" <<'BUILD'
#!/usr/bin/env bash
count_file="$1"; message="$2"
runs=$(( $(cat "$count_file" 2>/dev/null || echo 0) + 1 ))
echo "$runs" > "$count_file"
if [[ $runs -eq 1 ]]; then printf '%s\n' "$message"; exit 1; fi
echo "BUILD SUCCESSFUL"
BUILD
chmod +x "$work/build.sh"

runs_for() { # <name> <message>: how many times the build ran, and the status retry-network.sh gave
    local count="$work/$1.count"
    rm -f "$count"
    local status=0
    RETRY_NETWORK_WAIT=0 "$compose/retry-network.sh" "$work/build.sh" "$count" "$2" >/dev/null 2>&1 || status=$?
    echo "$(cat "$count") $status"
}

echo "retry-network.sh"

# A plugin the portal did not answer for: Gradle names no HTTP error, only that it was not found
# in any repository (fork-train-10, the ubuntu publish job, while the same lookup passed elsewhere).
plugin="Plugin [id: 'org.jetbrains.kotlin.plugin.serialization', version: '2.3.20'] was not found in any of the following sources:
- Plugin Repositories (could not resolve plugin artifact 'org.jetbrains.kotlin.plugin.serialization:org.jetbrains.kotlin.plugin.serialization.gradle.plugin:2.3.20')"
[[ "$(runs_for plugin "$plugin")" == "2 0" ]] || fail "a plugin the portal did not answer for is not run again"

# Central rate limiting (fork-train-9, a web test job).
limited="> Could not GET 'https://repo.maven.apache.org/maven2/com/google/protobuf/protobuf-java/3.25.5/protobuf-java-3.25.5.pom'. Received status code 429 from server: Too Many Requests"
[[ "$(runs_for limited "$limited")" == "2 0" ]] || fail "a 429 from Central is not run again"
limited_alone="Received status code 429 from server: Too Many Requests"
[[ "$(runs_for limited_alone "$limited_alone")" == "2 0" ]] || fail "a 429 without Could not GET is not run again"

# The handshake drop it was written for still runs again.
handshake="> Remote host terminated the handshake"
[[ "$(runs_for handshake "$handshake")" == "2 0" ]] || fail "a dropped handshake is not run again"

# A failure in the code is not: the same message would come back.
compile="e: file:///x/Foo.kt:3:5 Unresolved reference 'bar'."
[[ "$(runs_for compile "$compile")" == "1 1" ]] || fail "a compile error is run again"

# A variant that does not exist is not either: no attempt can resolve it (fork-train-10,
# skiko for mingwX64 in check_public_api).
variant="> Could not resolve org.jetbrains.skiko:skiko:0.144.6.
   > No matching variant of org.jetbrains.skiko:skiko:0.144.6 was found."
[[ "$(runs_for variant "$variant")" == "1 1" ]] || fail "a missing variant is run again"

if [[ $failures -gt 0 ]]; then
    echo "$failures failed" >&2
    exit 1
fi
echo "ok"
