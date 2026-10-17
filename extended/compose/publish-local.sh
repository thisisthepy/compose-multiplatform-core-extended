#!/usr/bin/env bash
# Publishes the Compose modules an application draws with, for the targets named, to the local
# Maven repository under this fork's coordinates (extended/COORDINATES.md).
#
# Usage: extended/compose/publish-local.sh [--list] <target>... [-- <gradle argument>...]
#
#   targets  macosArm64, linuxX64, linuxArm64, mingwX64, desktop
#   --list   prints the Gradle tasks it would run, one per line, and runs nothing; a task
#            starting with `?` is run only where the project has it
#
# Runs from anywhere; the Gradle build is the checkout this script is in. Every module is
# published at the version gradle.properties gives its line (1.11.1-ext.1 for Compose), so a
# consumer names exactly that and nothing JetBrains publishes can stand in for it.
#
# Why a list of modules rather than everything: the closure of what an application draws with
# (foundation, material3 and what they reach) is what a consumer needs, and a module left off
# it is not a failure here but an unresolvable coordinate in the consumer's build, which names
# a sibling at this fork's version. Material 2's navigation, the adaptive family and the
# navigation suite are left off because each asks for a published artifact that has no Linux
# variant. The runtime, runtime-saveable and the internal annotation and collection libraries
# are on it although they carry no classes: they are compatibility stubs that point at the
# androidx artifacts, and the modules above name them at this fork's version.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"

die() {
    echo "error: $1" >&2
    shift
    for line in "$@"; do echo "       $line" >&2; done
    exit 1
}

# The modules every target needs.
common_modules=(
    annotation:annotation
    collection:collection
    compose:runtime:runtime
    compose:runtime:runtime-saveable
    compose:animation:animation
    compose:animation:animation-core
    compose:foundation:foundation
    compose:foundation:foundation-layout
    compose:material:material-ripple
    compose:material3:material3
    compose:ui:ui
    compose:ui:ui-backhandler
    compose:ui:ui-geometry
    compose:ui:ui-graphics
    compose:ui:ui-text
    compose:ui:ui-tooling-preview
    compose:ui:ui-unit
    compose:ui:ui-util
)
# What a desktop application reaches beyond those: compose.desktop.currentOs is the desktop
# module and its per-system jar, which bring Material 2, and compose.desktop.uiTestJUnit4 is
# ui-test-junit4 and ui-test.
desktop_modules=(
    compose:material:material
    compose:ui:ui-test
    compose:ui:ui-test-junit4
)
# The per-system publications of the desktop module, whatever system builds them: the jar
# names its natives by coordinate and carries none of them itself.
desktop_systems=(linux-x64 linux-arm64 macos-x64 macos-arm64 windows-x64 windows-arm64)

list=0
targets=()
gradle_args=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        --list) list=1; shift ;;
        --) shift; gradle_args=("$@"); break ;;
        -h|--help) sed -n '2,21p' "$0"; exit 0 ;;
        macosArm64|linuxX64|linuxArm64|mingwX64|desktop) targets+=("$1"); shift ;;
        *) die "unknown argument '$1'" "known targets: macosArm64, linuxX64, linuxArm64, mingwX64, desktop" ;;
    esac
done
[[ ${#targets[@]} -gt 0 ]] || die "no target given" "usage: publish-local.sh [--list] <target>... [-- <gradle argument>...]"

tasks=()
add_task() {
    local task
    for task in ${tasks[@]+"${tasks[@]}"}; do [[ "$task" == "$1" ]] && return 0; done
    tasks+=("$1")
}
# Two publications per module, not one. The target's own carries the klib or jar; the root
# says which targets exist, and without it a consumer is told the module does not support
# the platform. On a module the build redirects, a redirecting root (Decorated) replaces the
# plain one, which is then skipped; other modules have no Decorated publication at all. Both
# are asked for, as optional tasks (a leading `?`, see publish-local.init.gradle).
add_module() {
    # annotation and collection declare a plain jvm() target where Compose declares desktop(),
    # so their desktop publication is the Jvm one.
    if [[ "$2" == Desktop && ( "$1" == annotation:annotation || "$1" == collection:collection ) ]]; then
        add_task ":$1:publishJvmPublicationToMavenLocal"
    else
        add_task ":$1:publish$2PublicationToMavenLocal"
    fi
    add_task "?:$1:publishKotlinMultiplatformPublicationToMavenLocal"
    add_task "?:$1:publishKotlinMultiplatformDecoratedPublicationToMavenLocal"
}
windows=0
for target in "${targets[@]}"; do
    case "$target" in
        desktop) publication=Desktop ;;
        macosArm64) publication=MacosArm64 ;;
        linuxX64) publication=LinuxX64 ;;
        linuxArm64) publication=LinuxArm64 ;;
        mingwX64) publication=MingwX64; windows=1 ;;
    esac
    for module in "${common_modules[@]}"; do add_module "$module" "$publication"; done
    # Compose UI's shared metadata names ui-uikit, its iOS half. Its root is asked for wherever
    # the build has one, so a consumer resolving that metadata finds it.
    add_task "?:compose:ui:ui-uikit:publishKotlinMultiplatformPublicationToMavenLocal"
    add_task "?:compose:ui:ui-uikit:publishKotlinMultiplatformDecoratedPublicationToMavenLocal"
    if [[ "$target" == desktop ]]; then
        for module in "${desktop_modules[@]}"; do add_module "$module" "$publication"; done
        add_task "?:compose:desktop:desktop:publishKotlinMultiplatformPublicationToMavenLocal"
        add_task ":compose:desktop:desktop:publishJvmPublicationToMavenLocal"
        for system in "${desktop_systems[@]}"; do
            add_task ":compose:desktop:desktop:publishJvm${system}PublicationToMavenLocal"
        done
    fi
done

if [[ $list -eq 1 ]]; then
    printf '%s\n' "${tasks[@]}"
    exit 0
fi

[[ -n "${JAVA_HOME:-}" ]] || die "JAVA_HOME is not set" "The Compose build needs a JDK 17 or 21."

# JavaScript and Wasm are never built here, and Windows only when asked for: skiko publishes no
# mingwX64 artifact unless extended/skiko built one, and Gradle resolves every platform it has
# enabled, whatever task it was asked to run.
platforms="-JS,-WASM"
[[ $windows -eq 1 ]] || platforms="$platforms,-WINDOWS"
echo "==> publishing ${#tasks[@]} task(s) for ${targets[*]} ($platforms)"
cd "$root"
./gradlew --no-daemon --no-configuration-cache \
    --init-script "$here/publish-local.init.gradle" \
    "-Pandroidx.enabled.kmp.target.platforms=$platforms" \
    "-Pextended.publish.tasks=$(IFS=,; echo "${tasks[*]}")" \
    ${gradle_args[@]+"${gradle_args[@]}"} \
    extendedPublishLocal

echo "==> published to $HOME/.m2/repository/org/thisisthepy/compose"
