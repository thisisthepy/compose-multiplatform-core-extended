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

# A root lists a variant for every target the build configured; a dependency of a target that
# was not built here (ui-uikit, iOS only) is not one a consumer of the built targets reaches.
roots="$work/roots"
mkdir -p "$roots/org/thisisthepy/compose/ui/ui/1.11.1-ext.1" "$roots/org/thisisthepy/compose/ui/ui-linuxx64/1.11.1-ext.1"
printf '{"variants":[{"name":"linuxX64ApiElements-published","dependencies":[]},{"name":"iosArm64ApiElements-published","dependencies":[%s]}]}\n' \
    "$(dep org.thisisthepy.compose.ui ui-uikit 1.11.1-ext.1)" > "$roots/org/thisisthepy/compose/ui/ui/1.11.1-ext.1/ui-1.11.1-ext.1.module"
printf '{"variants":[{"name":"linuxX64ApiElements-published","dependencies":[]}]}\n' > "$roots/org/thisisthepy/compose/ui/ui-linuxx64/1.11.1-ext.1/ui-linuxx64-1.11.1-ext.1.module"
printf '<project><dependencyManagement><dependencies><dependency><groupId>org.thisisthepy.compose.ui</groupId><artifactId>ui-tooling</artifactId><version>1.11.1-ext.1</version></dependency></dependencies></dependencyManagement></project>\n' \
    > "$roots/org/thisisthepy/compose/ui/ui/1.11.1-ext.1/ui-1.11.1-ext.1.pom"
python3 -I "$compose/check-local.py" "$roots" >/dev/null || fail "an unbuilt target's dependency or a managed version is reported as missing"
printf '{"variants":[{"name":"linuxX64ApiElements-published","dependencies":[%s]}]}\n' "$(dep org.thisisthepy.compose.ui ui-text 1.11.1-ext.1)" \
    > "$roots/org/thisisthepy/compose/ui/ui-linuxx64/1.11.1-ext.1/ui-linuxx64-1.11.1-ext.1.module"
out="$(python3 -I "$compose/check-local.py" "$roots" || true)"
grep -q "ui-text:1.11.1-ext.1 is not published" <<< "$out" || fail "a built target's missing dependency is not reported"

echo "publish-local.sh --list"

tasks="$("$compose/publish-local.sh" --list macosArm64)"
grep -qx ':compose:ui:ui:publishMacosArm64PublicationToMavenLocal' <<< "$tasks" || fail "macosArm64 does not publish ui for its target"
grep -qx '?:compose:ui:ui:publishKotlinMultiplatformDecoratedPublicationToMavenLocal' <<< "$tasks" || fail "the root of ui is not asked for"
grep -qx ':compose:runtime:runtime:publishMacosArm64PublicationToMavenLocal' <<< "$tasks" || fail "the runtime stub the modules name is not published"
desktop="$("$compose/publish-local.sh" --list desktop)"
grep -qx ':compose:desktop:desktop:publishJvmmacos-arm64PublicationToMavenLocal' <<< "$desktop" || fail "the desktop module's macOS jar is not published"
grep -qx ':compose:ui:ui-test-junit4:publishDesktopPublicationToMavenLocal' <<< "$desktop" || fail "ui-test-junit4 is not published for the desktop"
grep -qx ':annotation:annotation:publishJvmPublicationToMavenLocal' <<< "$desktop" || fail "annotation's desktop publication is not its Jvm one"

ios="$("$compose/publish-local.sh" --list iosArm64 js wasmJs)"
grep -qx '?:compose:ui:ui:publishIosArm64PublicationToMavenLocal' <<< "$ios" || fail "iosArm64 does not publish ui for its target"
grep -qx '?:compose:ui:ui-uikit:publishIosArm64PublicationToMavenLocal' <<< "$ios" || fail "iosArm64 does not publish ui-uikit"
grep -qx '?:compose:ui:ui:publishJsPublicationToMavenLocal' <<< "$ios" || fail "js does not publish ui"
grep -qx '?:compose:ui:ui:publishWasmJsPublicationToMavenLocal' <<< "$ios" || fail "wasmJs does not publish ui"
grep -qx '?:lifecycle:lifecycle-common:publishIosArm64PublicationToMavenLocal' <<< "$ios" || fail "lifecycle-common is not published for iOS"
grep -qx '?:navigation:navigation-compose:publishWasmJsPublicationToMavenLocal' <<< "$ios" || fail "navigation-compose is not published for the web"
grep -qx '?:compose:material3:adaptive:adaptive:publishJsPublicationToMavenLocal' <<< "$ios" || fail "adaptive is not published for the web"
tvos="$("$compose/publish-local.sh" --list tvosArm64 watchosArm32)"
grep -qx '?:compose:runtime:runtime:publishTvosArm64PublicationToMavenLocal' <<< "$tvos" || fail "the runtime is not published for tvOS"
grep -qx '?:savedstate:savedstate:publishWatchosArm32PublicationToMavenLocal' <<< "$tvos" || fail "savedstate is not published for watchOS"
! grep -q 'ui:ui:publishTvos' <<< "$tvos" || fail "ui is published for tvOS, which upstream does not"
linux="$("$compose/publish-local.sh" --list linuxX64 mingwX64)"
grep -qx '?:window:window-core:publishLinuxX64PublicationToMavenLocal' <<< "$linux" || fail "window-core is not published for Linux"
grep -qx '?:savedstate:savedstate:publishMingwX64PublicationToMavenLocal' <<< "$linux" || fail "savedstate is not published for mingwX64"
! grep -q 'adaptive' <<< "$linux" || fail "the adaptive family is published for Linux, which upstream does not"

if [[ "$failures" -eq 0 ]]; then echo "  ok"; else exit 1; fi
