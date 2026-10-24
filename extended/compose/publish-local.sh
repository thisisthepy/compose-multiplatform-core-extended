#!/usr/bin/env bash
# Publishes the Compose modules an application draws with, for the targets named, to the local
# Maven repository under this fork's coordinates (extended/COORDINATES.md).
#
# Usage: extended/compose/publish-local.sh [--list] <target>... [-- <gradle argument>...]
#
#   targets    desktop, macosArm64, linuxX64, linuxArm64, mingwX64, iosArm64, iosSimulatorArm64,
#              tvosArm64, tvosSimulatorArm64, watchosArm64, watchosArm32, watchosSimulatorArm64,
#              js, wasmJs
#   --list     prints the Gradle tasks it would run, one per line, and runs nothing; a task
#              starting with `?` is run only where the project has it
#   --all-modules  also publishes the libraries and modules upstream lists beyond the ones an
#              application draws with: lifecycle, savedstate, navigation, window-core, the
#              adaptive family and so on. Off, a run publishes what it always has; on, it needs
#              every Apple target built too, because those libraries' shared metadata names them.
#   --all-roots  configures every platform group that does not need something this host lacks
#              (JavaScript and Wasm; Windows only with mingwX64), so each module's root lists the
#              targets other hosts build. The run that publishes the roots takes this.
#
# Runs from anywhere; the Gradle build is the checkout this script is in. Every module is
# published at the version gradle.properties gives its line (1.11.1-ext.1 for Compose), so a
# consumer names exactly that and nothing JetBrains publishes can stand in for it.
#
# Why lists of modules rather than everything: each target gets the modules upstream publishes
# for it (JetBrainsPublication.kt in buildSrc), and a module left off is an unresolvable
# coordinate in the consumer's build, which names a sibling at this fork's version.
# extended/publish/check_targets.py holds the release to that catalogue. Linux and Windows get
# the modules this fork has always built for them and the libraries upstream publishes for every
# platform (lifecycle, savedstate, navigation-common and -runtime, window-core); the adaptive
# family, Material 2's navigation and the navigation suite are Skiko modules, so desktop, macOS,
# iOS and the web. The runtime, runtime-saveable and the internal annotation and collection
# libraries are on every target although they carry no classes: they are compatibility stubs
# that point at the androidx artifacts, and the modules above name them at this fork's version.
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

# What the other libraries of this fork reach, by what upstream publishes them for
# (JetBrainsPublication.kt in buildSrc). Each is asked for as an optional task: a module that has
# no such target is skipped, and extended/publish/check_targets.py fails the bundle when a
# target upstream has is missing from it.
#
# The four libraries every platform has, tvOS and watchOS included.
base_all_modules=(
    annotation:annotation
    collection:collection
    compose:runtime:runtime
    compose:runtime:runtime-saveable
)
# Every platform upstream lists, ComposePlatforms.ALL: no Skiko, so Linux and Windows have them.
all_platform_modules=(
    lifecycle:lifecycle-common
    lifecycle:lifecycle-runtime
    lifecycle:lifecycle-viewmodel
    lifecycle:lifecycle-viewmodel-savedstate
    lifecycle:lifecycle-runtime-compose
    savedstate:savedstate
    savedstate:savedstate-compose
    navigation:navigation-common
    navigation:navigation-runtime
    window:window-core
)
# The Skiko platforms (ComposePlatforms.SKIKO_SUPPORT): desktop, macOS, iOS and the web.
skiko_modules=(
    compose:animation:animation-graphics
    compose:material:material
    compose:material:material-navigation
    compose:material3:material3-window-size-class
    compose:material3:material3-adaptive-navigation-suite
    compose:material3:adaptive:adaptive
    compose:material3:adaptive:adaptive-layout
    compose:material3:adaptive:adaptive-navigation
    compose:material3:adaptive:adaptive-navigation3
    compose:ui:ui-test
    lifecycle:lifecycle-viewmodel-compose
    lifecycle:lifecycle-viewmodel-navigation3
    navigation:navigation-compose
    navigation3:navigation3-ui
    navigationevent:navigationevent-compose
)
# Desktop and Android only (ComposePlatforms.JVM_BASED).
jvm_only_modules=(
    compose:ui:ui-tooling
    compose:ui:ui-tooling-data
)

list=0
all_roots=0
all_modules=0
targets=()
gradle_args=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        --list) list=1; shift ;;
        --all-roots) all_roots=1; shift ;;
        --all-modules) all_modules=1; shift ;;
        --) shift; gradle_args=("$@"); break ;;
        -h|--help) sed -n '2,28p' "$0"; exit 0 ;;
        desktop|macosArm64|linuxX64|linuxArm64|mingwX64|iosArm64|iosSimulatorArm64|tvosArm64|tvosSimulatorArm64|watchosArm64|watchosArm32|watchosSimulatorArm64|js|wasmJs)
            targets+=("$1"); shift ;;
        *) die "unknown argument '$1'" "known targets: desktop, macosArm64, linuxX64, linuxArm64, mingwX64, iosArm64, iosSimulatorArm64," "tvosArm64, tvosSimulatorArm64, watchosArm64, watchosArm32, watchosSimulatorArm64, js, wasmJs" ;;
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
    # A third argument `?` makes the target's own publication optional too.
    local optional="${3:-}"
    # annotation, collection and lifecycle-common declare a plain jvm() target where Compose
    # declares desktop(), so their desktop publication is the Jvm one.
    if [[ "$2" == Desktop && ( "$1" == annotation:annotation || "$1" == collection:collection || "$1" == lifecycle:lifecycle-common ) ]]; then
        add_task "${optional}:$1:publishJvmPublicationToMavenLocal"
    else
        add_task "${optional}:$1:publish$2PublicationToMavenLocal"
    fi
    add_task "?:$1:publishKotlinMultiplatformPublicationToMavenLocal"
    add_task "?:$1:publishKotlinMultiplatformDecoratedPublicationToMavenLocal"
}
windows=0
js=0
wasm=0
for target in "${targets[@]}"; do
    # The publication is the target's name with a capital: what Kotlin names it.
    skiko_target=1
    case "$target" in
        desktop) publication=Desktop ;;
        macosArm64) publication=MacosArm64 ;;
        linuxX64) publication=LinuxX64; skiko_target=0 ;;
        linuxArm64) publication=LinuxArm64; skiko_target=0 ;;
        mingwX64) publication=MingwX64; windows=1; skiko_target=0 ;;
        iosArm64) publication=IosArm64 ;;
        iosSimulatorArm64) publication=IosSimulatorArm64 ;;
        tvosArm64) publication=TvosArm64; skiko_target=0 ;;
        tvosSimulatorArm64) publication=TvosSimulatorArm64; skiko_target=0 ;;
        watchosArm64) publication=WatchosArm64; skiko_target=0 ;;
        watchosArm32) publication=WatchosArm32; skiko_target=0 ;;
        watchosSimulatorArm64) publication=WatchosSimulatorArm64; skiko_target=0 ;;
        js) publication=Js; js=1 ;;
        wasmJs) publication=WasmJs; wasm=1 ;;
    esac
    # The modules this fork has always published for the targets it has always published.
    # Only the targets that are new ask for them as optional: not every module has them.
    required=""
    case "$target" in desktop|macosArm64|linuxX64|linuxArm64|mingwX64) ;; *) required="?" ;; esac
    # tvOS and watchOS carry only what upstream lists for every platform, without Skiko.
    case "$target" in
        tvos*|watchos*) for module in "${base_all_modules[@]}"; do add_module "$module" "$publication" "$required"; done ;;
        *) for module in "${common_modules[@]}"; do add_module "$module" "$publication" "$required"; done ;;
    esac
    [[ $all_modules -eq 0 ]] || for module in "${all_platform_modules[@]}"; do add_module "$module" "$publication" "?"; done
    if [[ $skiko_target -eq 1 && $all_modules -eq 1 ]]; then
        for module in "${skiko_modules[@]}"; do add_module "$module" "$publication" "?"; done
    fi
    if [[ "$target" == iosArm64 || "$target" == iosSimulatorArm64 ]]; then
        add_task "?:compose:ui:ui-uikit:publish${publication}PublicationToMavenLocal"
    fi
    # Compose UI's shared metadata names ui-uikit, its iOS half. Its root is asked for wherever
    # the build has one, so a consumer resolving that metadata finds it.
    add_task "?:compose:ui:ui-uikit:publishKotlinMultiplatformPublicationToMavenLocal"
    add_task "?:compose:ui:ui-uikit:publishKotlinMultiplatformDecoratedPublicationToMavenLocal"
    if [[ "$target" == desktop ]]; then
        for module in "${desktop_modules[@]}"; do add_module "$module" "$publication"; done
        [[ $all_modules -eq 0 ]] || for module in "${jvm_only_modules[@]}"; do add_module "$module" "$publication" "?"; done
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

# JavaScript and Wasm are built only when asked for, or configured for the roots (--all-roots),
# and Windows only when asked for: skiko publishes no mingwX64 artifact unless extended/skiko
# built one, and Gradle resolves every platform it has enabled, whatever task it was asked to run.
platforms=""
add_platform() { platforms="${platforms:+$platforms,}$1"; }
[[ $js -eq 1 || $all_roots -eq 1 ]] || add_platform "-JS"
[[ $wasm -eq 1 || $all_roots -eq 1 ]] || add_platform "-WASM"
[[ $windows -eq 1 ]] || add_platform "-WINDOWS"
echo "==> publishing ${#tasks[@]} task(s) for ${targets[*]} ($platforms)"
cd "$root"
./gradlew --no-daemon --no-configuration-cache \
    --init-script "$here/publish-local.init.gradle" \
    ${platforms:+"-Pandroidx.enabled.kmp.target.platforms=$platforms"} \
    "-Pextended.publish.tasks=$(IFS=,; echo "${tasks[*]}")" \
    ${gradle_args[@]+"${gradle_args[@]}"} \
    extendedPublishLocal

echo "==> published to $HOME/.m2/repository/org/thisisthepy/compose"
