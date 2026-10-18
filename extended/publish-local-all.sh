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
#   org.thisisthepy.compose.skiko:skiko-mingwx64      extended/skiko/build-skiko-mingw.sh, then
#                                                     republish-skiko-mingw.py (mingwX64)
#   org.thisisthepy.compose.window:<module>           extended/window, the modules this host builds
#   org.thisisthepy.compose:compose-gradle-plugin     the plugins, at PLUGIN_REVISION: Compose's, and
#   org.thisisthepy.kotlin:kotlin-native-desktop-...  the one that links a Windows executable with MSVC
#
# Which host publishes what. A Kotlin Multiplatform library's root (its .module and the
# metadata of its shared source sets) has to be written by one build that has every target, or a
# consumer's shared source set misses the targets another host built. Kotlin/Native compiles
# every desktop target from macOS, so a Mac publishes everything with a root: Compose for the
# desktop JVM, macosArm64, linuxX64, linuxArm64 and mingwX64 (with skiko for mingwX64, which
# skiko does not publish: its Kotlin half and, as an msvc zip, its C++ half), skiko-awt,
# the window modules but two, and the plugin. The two that need their own system:
#
#   native/linux     its cinterop reads the system's X11, GL and XIM headers; on Linux x86-64
#                    (Kotlin/Native has no arm64 Linux host), against the Compose a Mac published
#   windows-shared   its C half is compiled and tested with MSVC; on Windows (Git Bash)
#
# --targets overrides the Compose targets on any host, for a local build of fewer of them.
#
# Needs what each step needs: a JDK 21 in JAVA_HOME (the Compose build and the plugin), git,
# python3, on macOS the MSVC runtime and Windows SDK headers as xwin lays them out (XWIN_DIR, for
# skiko's C++ half for mingwX64), on Linux the X11, GL, fontconfig and FreeType development
# headers, and on Windows MSVC.
set -euo pipefail

# The plugin commit published with this fork's commit. Its aliases name the version above.
PLUGIN_REPOSITORY="https://github.com/thisisthepy/compose-multiplatform-extended.git"
PLUGIN_REVISION="2e84f61ebf3448a96ddd5a292163ae65c055d299"

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/.." && pwd)"

die() {
    echo "error: $1" >&2
    shift
    for line in "$@"; do echo "       $line" >&2; done
    exit 1
}

# The PATH kotlin.bat runs with on Windows: System32 first, and none of Git's own directories
# but Git\cmd (git itself). Started from Git Bash, kotlin.bat would otherwise unpack its toolchain
# with Git's GNU tar, which takes C:\... for a remote host ("Cannot connect to C: resolve failed").
windows_tool_path() {
    local git_root entry native kept
    git_root="$(cygpath -w / | tr 'A-Z' 'a-z')"
    git_root="${git_root%\\}"
    kept="$(cygpath -u "${SYSTEMROOT:-C:\\Windows}")/System32"
    local IFS=:
    for entry in $PATH; do
        native="$(cygpath -w "$entry" 2>/dev/null | tr 'A-Z' 'a-z')"
        native="${native%\\}"
        if [[ "$native" == "$git_root" || "$native" == "$git_root"\\* ]] && [[ "$native" != "$git_root\\cmd" ]]; then
            continue
        fi
        kept="$kept:$entry"
    done
    echo "$kept"
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
window_modules=()
native_linux=0
windows_host=0
full_host=0
case "$os" in
    Darwin)
        full_host=1
        if [[ -z "$targets" ]]; then
            targets="desktop macosArm64 linuxX64 linuxArm64 mingwX64"
            xwin="${XWIN_DIR:-$HOME/Library/Caches/cargo-xwin/xwin}"
            [[ -d "$xwin/sdk/include/ucrt" ]] || die "no MSVC runtime and Windows SDK headers under $xwin" \
                "skiko's C++ half for mingwX64 is compiled against them. Run" \
                "'xwin --accept-license splat --output $xwin' (or set XWIN_DIR), or pass --targets" \
                "without mingwX64 for a local build of fewer targets."
        fi
        window_modules=(common graalvm/graalvm-macos graalvm/graalvm-linux native/macos scene) ;;
    Linux)
        [[ "$arch" == x86_64 ]] || die "native/linux is built on Linux x86-64; Kotlin/Native has no arm64 Linux host"
        native_linux=1
        if [[ -z "$targets" ]]; then
            [[ -d "$HOME/.m2/repository/org/thisisthepy/compose/ui/ui-linuxx64/$version" ]] || die \
                "no Compose for linuxX64 at $version in the local repository" \
                "A Mac publishes it with every other root; take its repository, or pass" \
                "--targets \"linuxX64 linuxArm64\" to build Compose for Linux here."
        fi ;;
    MINGW*|MSYS*|CYGWIN*)
        windows_host=1
        # windows-shared names ../common, so common is in the build; only windows-shared is
        # this host's to ship, and the workflow takes nothing else from here.
        window_modules=(common windows-shared) ;;
    *) die "this script publishes from macOS, Linux or Windows (Git Bash); this is $os" ;;
esac
mkdir -p "$work"

if [[ $windows_host -eq 0 && -n "$targets" ]]; then
    others=()
    for target in $targets; do [[ "$target" == mingwX64 ]] || others+=("$target"); done
    if [[ ${#others[@]} -gt 0 ]]; then
        echo "==> 1. Compose for ${others[*]}, as $version"
        "$here/compose/publish-local.sh" "${others[@]}"
    fi
    if [[ " $targets " == *" mingwX64 "* ]]; then
        # skiko publishes no mingwX64 artifact. build-skiko-mingw.sh builds both halves: the Kotlin
        # half, which Compose for mingwX64 compiles against, and the C++ half with Skia, which
        # is MSVC's and so cannot sit in the klib the way JetBrains' halves do. Both then ship
        # as org.thisisthepy.compose.skiko:skiko-mingwx64, the C++ half as its msvc zip, which
        # org.thisisthepy.kotlin.native.desktop links into a Windows executable, and Compose's
        # mingwX64 modules are pointed at it (republish-skiko-mingw.py says why).
        echo "==> 1b. skiko for mingwX64 (both halves), then Compose for mingwX64"
        XWIN_DIR="${xwin:-${XWIN_DIR:-}}" "$here/skiko/build-skiko-mingw.sh" "$work/skiko-mingw"
        "$here/compose/publish-local.sh" mingwX64
        skiko_upstream="$(sed -n 's/^PUBLISHED_AS="\(.*\)"$/\1/p' "$here/skiko/build-skiko-mingw.sh")"
        python3 "$here/skiko/republish-skiko-mingw.py" "$HOME/.m2/repository" "$skiko_upstream" "$version" \
            "$work/skiko-mingw/out/windows-x64"
    fi
fi

if [[ $full_host -eq 1 && " $targets " == *" desktop "* ]]; then
    echo "==> 2. skiko-awt with the AWT switch"
    "$here/skiko/build-skiko-awt.sh" "$work/skiko-awt"
fi

if [[ ${#window_modules[@]} -gt 0 ]]; then
echo "==> 3. the window modules: ${window_modules[*]}"
(
    cd "$here/window"
    cp project.yaml "$work/project.yaml.all"
    trap 'cp "$work/project.yaml.all" project.yaml' EXIT
    {
        echo "modules:"
        for module in "${window_modules[@]}"; do echo "  - $module"; done
    } > project.yaml
    if [[ $windows_host -eq 1 ]]; then
        PATH="$(windows_tool_path)" cmd //c kotlin.bat publish mavenLocal
    else
        ./kotlin publish mavenLocal
    fi
)
fi
if [[ $native_linux -eq 1 ]]; then
    "$here/window/scripts/publish-native-linux.sh"
fi

if [[ $full_host -eq 0 ]]; then
    echo "published this host's part as $version; Compose's roots, skiko-awt, the other window modules and the plugin come from a Mac"
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
        :kotlin-native-desktop:publishToMavenLocal \
        "-Pdeploy.version=$version" "-Pcompose.version=$version" "-Pcompose.material3.version=$version"
)

echo "==> 5. what is in the local repository"
python3 "$here/compose/check-local.py"

echo
echo "published $version to $HOME/.m2/repository/org/thisisthepy/compose"
