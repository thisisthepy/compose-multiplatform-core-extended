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
# The targets default to what this host builds: desktop and macosArm64 on a Mac, desktop and both
# Linux architectures on Linux x86-64. The window modules follow the host the same way: native/macos
# and the scene on a Mac, native/linux on Linux x86-64 (Kotlin/Native has no arm64 Linux host).
#
# Needs what each step needs: a JDK 21 in JAVA_HOME (the Compose build and the plugin), git,
# python3, and on Linux the X11, GL, fontconfig and FreeType development headers.
set -euo pipefail

# The plugin commit published with this fork's commit. Its aliases name the version above.
PLUGIN_REPOSITORY="https://github.com/thisisthepy/compose-multiplatform-extended.git"
PLUGIN_REVISION="PLUGIN_REVISION_PLACEHOLDER"

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
case "$os" in
    Darwin)
        [[ -n "$targets" ]] || targets="desktop macosArm64"
        window_modules+=(native/macos scene) ;;
    Linux)
        if [[ "$arch" == x86_64 ]]; then
            [[ -n "$targets" ]] || targets="desktop linuxX64 linuxArm64"
            native_linux=1
        else
            [[ -n "$targets" ]] || targets="desktop"
        fi ;;
    *) die "this script publishes from macOS or Linux; this is $os" ;;
esac
mkdir -p "$work"

echo "==> 1. Compose for $targets, as $version"
# shellcheck disable=SC2086
"$here/compose/publish-local.sh" $targets

if [[ " $targets " == *" desktop "* ]]; then
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
    ./kotlin publish mavenLocal
)
if [[ $native_linux -eq 1 ]]; then
    "$here/window/scripts/publish-native-linux.sh"
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
