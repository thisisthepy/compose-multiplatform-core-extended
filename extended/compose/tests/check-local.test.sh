#!/usr/bin/env bash
# Tests check-local.py and publish-local.sh --list against made-up repositories, with no build.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
compose="$(cd "$here/.." && pwd)"
work="$(mktemp -d "${RUNNER_TEMP:-$here}/check-local.XXXXXX")"
trap 'rm -rf "$work"' EXIT
failures=0
fail() { echo "  FAIL: $1" >&2; failures=$((failures + 1)); }

module() { # <repo> <group path> <artifact> <version> <json dependencies>
    mkdir -p "$1/$2/$3/$4"
    printf '{"variants":[{"name":"a","dependencies":[%s]}]}\n' "$5" > "$1/$2/$3/$4/$3-$4.module"
}
dep() { printf '{"group":"%s","module":"%s","version":{"requires":"%s"}}' "$1" "$2" "$3"; }

echo "check-local.py"

good="$work/good"
module "$good" org/thisisthepy/compose/ui ui 1.11.1-ext.1 \
    "$(dep org.thisisthepy.compose.ui ui-util 1.11.1-ext.1),$(dep org.jetbrains.compose.runtime runtime 1.11.1)"
module "$good" org/thisisthepy/compose/ui ui-util 1.11.1-ext.1 ""
python3 -I "$compose/check-local.py" "$good" >/dev/null || fail "a complete repository is reported as broken"

missing="$work/missing"
module "$missing" org/thisisthepy/compose/ui ui 1.11.1-ext.1 "$(dep org.thisisthepy.compose.ui ui-text 1.11.1-ext.1)"
out="$(python3 -I "$compose/check-local.py" "$missing" || true)"
grep -q "ui-text:1.11.1-ext.1 is not published" <<< "$out" || fail "a sibling nobody published is not reported"

jetbrains="$work/jetbrains"
module "$jetbrains" org/thisisthepy/compose/ui ui 1.11.1-ext.1 "$(dep org.jetbrains.compose.ui ui-graphics 1.11.1)"
out="$(python3 -I "$compose/check-local.py" "$jetbrains" || true)"
grep -q "JetBrains' copy of a module this fork publishes" <<< "$out" || fail "a dependency on JetBrains' Compose UI is not reported"

shadow="$work/shadow"
module "$shadow" org/thisisthepy/compose/ui ui 1.11.1-ext.1 ""
mkdir -p "$shadow/org/jetbrains/compose/ui/ui/1.11.1-ext.1"
out="$(python3 -I "$compose/check-local.py" "$shadow" || true)"
grep -q "fork version at JetBrains' coordinates" <<< "$out" || fail "a fork version under org.jetbrains.compose is not reported"

python3 -I "$compose/check-local.py" "$work/empty" >/dev/null 2>&1 && fail "an empty repository passes"

echo "publish-local.sh --list"

tasks="$("$compose/publish-local.sh" --list macosArm64)"
grep -qx ':compose:ui:ui:publishMacosArm64PublicationToMavenLocal' <<< "$tasks" || fail "macosArm64 does not publish ui for its target"
grep -qx ':compose:ui:ui:publishKotlinMultiplatformDecoratedPublicationToMavenLocal' <<< "$tasks" || fail "the root of ui is not published"
grep -qx ':compose:runtime:runtime:publishMacosArm64PublicationToMavenLocal' <<< "$tasks" || fail "the runtime stub the modules name is not published"
desktop="$("$compose/publish-local.sh" --list desktop)"
grep -qx ':compose:desktop:desktop:publishJvmmacos-arm64PublicationToMavenLocal' <<< "$desktop" || fail "the desktop module's macOS jar is not published"
grep -qx ':compose:ui:ui-test-junit4:publishDesktopPublicationToMavenLocal' <<< "$desktop" || fail "ui-test-junit4 is not published for the desktop"

if [[ "$failures" -eq 0 ]]; then echo "  ok"; else exit 1; fi
