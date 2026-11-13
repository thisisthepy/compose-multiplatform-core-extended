#!/usr/bin/env bash
# Runs a build command again when it failed on the network, not on the code.
#
# Usage: retry-network.sh <command> [args...]
#
# Gradle gives up on a download that dl.google.com or Central drops mid handshake
# ("Remote host terminated the handshake", "Could not GET ..."), and on a hosted runner that
# can end a 45 minute build. Running the same command again in the same job keeps what is
# already built and downloaded, so the second attempt goes straight to what failed.
# Only a failure whose output names a network error is retried, up to RETRY_NETWORK_ATTEMPTS
# attempts in all (default 3), RETRY_NETWORK_WAIT seconds apart times the attempt (default 30).
# Any other failure returns at once with the command's status.
set -uo pipefail
attempts="${RETRY_NETWORK_ATTEMPTS:-3}"
wait_seconds="${RETRY_NETWORK_WAIT:-30}"
pattern='Remote host terminated the handshake|Could not GET|Could not HEAD|Could not download|Connection reset|Connect timed out|Read timed out|SocketTimeoutException|UnknownHostException|Temporary failure in name resolution|502 Bad Gateway|503 Service Unavailable|504 Gateway Time-out'
log="$(mktemp "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/retry-network.XXXXXX")"
trap 'rm -f "$log"' EXIT
attempt=1
while :; do
    "$@" 2>&1 | tee "$log"
    status=${PIPESTATUS[0]}
    [[ $status -eq 0 ]] && exit 0
    if (( attempt >= attempts )) || ! grep -Eq "$pattern" "$log"; then
        exit "$status"
    fi
    echo "retry-network: attempt $attempt of $attempts failed on the network; trying again in $((wait_seconds * attempt)) s" >&2
    sleep $((wait_seconds * attempt))
    attempt=$((attempt + 1))
done
