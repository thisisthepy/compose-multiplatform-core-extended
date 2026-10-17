#!/usr/bin/env bash
# Publishes everything this fork ships to the local Maven repository, on its one version line:
# Compose and the other library lines, skiko-awt with the AWT switch, the window modules, and the
# Gradle plugin from thisisthepy/compose-multiplatform-extended at a pinned commit.
#
# Usage: extended/publish-local-all.sh <work-dir> [--targets "<publish-local.sh targets>"]
#
# The version is gradle.properties' (jetbrains.publication.version.COMPOSE, 1.11.1-ext.1), and
# every artifact below is published at it, so a consumer pins one set:
#
#   org.thisisthepy.compose.<group>:<artifact>        extended/compose/publish-local.sh
#   org.thisisthepy.compose.skiko:skiko-awt           extended/skiko/build-skiko-awt.sh (desktop)
#   org.thisisthepy.compose.window:<module>           extended/window, the modules this host builds
#   org.thisisthepy.compose:compose-gradle-plugin     the plugin, at PLUGIN_REVISION
#
# The targets default to what this host builds: desktop and macosArm64 on a Mac; desktop, both
# Linux architectures and, when the MSVC runtime and Windows SDK headers are there (XWIN_DIR or
# cargo-xwin's cache), mingwX64 on Linux x86-64. Compose's Gradle build does not run on Windows,
# and Kotlin/Native compiles mingwX64 from Linux, so Windows' Compose and its skiko come from the
# Linux host. The window modules follow the host: native/macos and the scene on a Mac,
# native/linux on Linux x86-64 (Kotlin/Native has no arm64 Linux host), and on Windows (Git
# Bash) only common and windows-shared, which needs MSVC for its C half and nothing of Compose.
#
# Needs what each step needs: a JDK 21 in JAVA_HOME (the Compose build and the plugin), git,
# python3, on Linux the X11, GL, fontconfig and FreeType development headers, and for mingwX64
# the MSVC runtime and Windows SDK as cargo-xwin lays them out.
set -euo pipefail

# The plugin commit published with this fork's commit. Its aliases name the version above.
PLUGIN_REPOSITORY="https://github.com/thisisthepy/compose-multiplatform-extended.git"
PLUGIN_REVISION="1e52ea1bb938f555d6941381a0aa97ab422b0477"

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/.." && pwd)"

die() {
    echo "error: $1" >&2
    shift
    for line in "$@"; do echo "       $line" >&2; done
    exit 1
}

[[ $# -ge 1 ]] || die "no work directory given" "usage: publish-local-all.sh <work-dir> [--targets \"<targets>\"]"
work="$1"
shift
targets=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --targets) targets="${2:-}"; shift 2 ;;
        *) die "unknown argument '$1'" ;;
    esac
done
[[ -n "${JAVA_HOME:-}" ]] || die "JAVA_HOME is not set" "The Compose build and the plugin need a JDK 21."

version="$(sed -n 's/^jetbrains.publication.version.COMPOSE=//p' "$root/gradle.properties")"
[[ -n "$version" ]] || die "gradle.properties names no jetbrains.publication.version.COMPOSE"
while IFS='=' read -r key value; do
    [[ "$value" == "$version" ]] ||
        die "$key is $value, not the one version line $version" "Every line in gradle.properties publishes at the same version."
done < <(grep '^jetbrains.publication.version\.' "$root/gradle.properties")

os="$(uname -s)"
arch="$(uname -m)"
window_modules=(common graalvm/graalvm-macos graalvm/graalvm-linux)
native_linux=0
windows_host=0
xwin="${XWIN_DIR:-$HOME/.cache/cargo-xwin/xwin}"
case "$os" in
    Darwin)
        [[ -n "$targets" ]] || targets="desktop macosArm64"
        window_modules+=(native/macos scene) ;;
    Linux)
        if [[ "$arch" == x86_64 ]]; then
            if [[ -z "$targets" ]]; then
                targets="desktop linuxX64 linuxArm64"
                [[ -d "$xwin/sdk/include/ucrt" ]] && targets="$targets mingwX64"
            fi
            native_linux=1
        else
            [[ -n "$targets" ]] || targets="desktop"
        fi ;;
    MINGW*|MSYS*|CYGWIN*)
        windows_host=1
        window_modules=(common windows-shared) ;;
    *) die "this script publishes from macOS, Linux or Windows (Git Bash); this is $os" ;;
esac
mkdir -p "$work"

if [[ $windows_host -eq 0 ]]; then
    others=()
    for target in $targets; do [[ "$target" == mingwX64 ]] || others+=("$target"); done
    if [[ ${#others[@]} -gt 0 ]]; then
        echo "==> 1. Compose for ${others[*]}, as $version"
        "$here/compose/publish-local.sh" "${others[@]}"
    fi
    if [[ " $targets " == *" mingwX64 "* ]]; then
        # skiko publishes no mingwX64 artifact; Compose for mingwX64 resolves this one. Its
        # C++ half is compiled with Kotlin/Native's clang, which the builds above fetched.
        echo "==> 1b. skiko for mingwX64 (both halves), then Compose for mingwX64"
        XWIN_DIR="$xwin" "$here/skiko/build-skiko-mingw.sh" "$work/skiko-mingw"
        "$here/compose/publish-local.sh" mingwX64
    fi
fi

if [[ $windows_host -eq 0 && " $targets " == *" desktop "* ]]; then
    echo "==> 2. skiko-awt with the AWT switch"
    "$here/skiko/build-skiko-awt.sh" "$work/skiko-awt"
fi

echo "==> 3. the window modules: ${window_modules[*]}"
(
    cd "$here/window"
    cp project.yaml "$work/project.yaml.all"
    trap 'cp "$work/project.yaml.all" project.yaml' EXIT
    {
        echo "modules:"
        for module in "${window_modules[@]}"; do echo "  - $module"; done
    } > project.yaml
    if [[ $windows_host -eq 1 ]]; then cmd //c kotlin.bat publish mavenLocal; else ./kotlin publish mavenLocal; fi
)
if [[ $native_linux -eq 1 ]]; then
    "$here/window/scripts/publish-native-linux.sh"
fi

if [[ $windows_host -eq 1 ]]; then
    echo "published the Windows window modules as $version; Compose, skiko and the plugin come from a Linux or macOS host"
    exit 0
fi

echo "==> 4. the Gradle plugin at $PLUGIN_REVISION"
plugin="$work/compose-multiplatform-extended"
if [[ ! -d "$plugin/.git" ]]; then
    git init -q "$plugin"
    git -C "$plugin" remote add origin "$PLUGIN_REPOSITORY"
fi
git -C "$plugin" cat-file -e "$PLUGIN_REVISION^{commit}" 2>/dev/null ||
    git -C "$plugin" fetch -q --depth 1 origin "$PLUGIN_REVISION"
git -C "$plugin" -c advice.detachedHead=false checkout -q --force "$PLUGIN_REVISION"
(
    cd "$plugin/gradle-plugins"
    ./gradlew --no-daemon :compose:publishToMavenLocal :jdk-version-probe:publishToMavenLocal \
        "-Pdeploy.version=$version" "-Pcompose.version=$version" "-Pcompose.material3.version=$version"
)

echo "==> 5. what is in the local repository"
python3 "$here/compose/check-local.py"

echo
echo "published $version to $HOME/.m2/repository/org/thisisthepy/compose"
