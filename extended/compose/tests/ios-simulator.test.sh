#!/usr/bin/env bash
# Checks that every xcodebuild in the workflows that runs on an iOS Simulator names the
# simulator by its id, found with simctl, and not by name. A name with no OS means the newest
# runtime the image has, and some macos-15 images had no iPhone 16 on it: fork-train-22's
# "Run iOS Utils Tests" failed with "Unable to find a device matching the provided destination
# specifier". Nothing is built.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
workflows="${1:-$(cd "$here/../../.." && pwd)/.github/workflows}"
failures=0
fail() { echo "  FAIL: $1" >&2; failures=$((failures + 1)); }

echo "ios simulator"
checked=0
while IFS= read -r line; do
    checked=$((checked + 1))
    case "$line" in
        *"platform=iOS Simulator,id="*) ;;
        *) fail "names an iOS Simulator by something other than its id: $line" ;;
    esac
done < <(grep -HnE -- "-destination +['\"]platform=iOS Simulator" "$workflows"/*.yml || true)
[[ $checked -gt 0 ]] || fail "no workflow under $workflows runs xcodebuild on an iOS Simulator"

if [[ $failures -gt 0 ]]; then
    echo "$failures failed" >&2
    exit 1
fi
echo "ok: $checked destinations"
