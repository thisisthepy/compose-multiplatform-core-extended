#!/usr/bin/env bash
# Builds skiko for Kotlin/Native on Windows (mingwX64), which skiko does not publish.
#
# skiko is two halves meant for two ABIs. The Kotlin half is compiled by Kotlin/Native,
# whose only Windows target is MinGW. The C++ half, the bridges the Kotlin half calls by
# name, has to match JetBrains' prebuilt Windows Skia, which is MSVC, and MinGW cannot
# produce that. So this does two builds:
#
#   1. skiko at a pinned revision with `0001-mingw-x64-target.patch` applied, publishing
#      the Kotlin half to the local Maven repository as
#      org.jetbrains.skiko:skiko-mingwx64:<version>, and skiko's root metadata with two
#      mingw_x64 variants added (extend-skiko-root.py says why the root matters).
#   2. The C++ half compiled with clang in MSVC mode against the prebuilt Windows Skia,
#      with skiko's own Windows defines: a class laid out differently on two sides of a call
#      is a crash nobody can trace from either side.
#
# Kept here as a patch rather than a fork of skiko, by decision: a mingwX64 skiko will not
# need publishing for a long time, and a fork is a repository to keep alive.
#
# Usage: build-skiko-mingw.sh <work-dir> [--clean]
#
# Output, in <work-dir>/out/windows-x64/:
#   skiko-bridges.lib   skiko's C++ half, MSVC, static C runtime (/MT)
#   skia/               the prebuilt Skia libraries the bridges link against
#   skia-include/       Skia's distribution, for anything else compiled against it
#
# Needs: git, curl, unzip, python3, a JDK 17 or 21 in JAVA_HOME (the Kotlin Gradle plugin
# cannot read the version string of some GraalVM distributions), Kotlin/Native's LLVM in
# ~/.konan (present after any Kotlin/Native build), and the MSVC runtime and Windows SDK as
# cargo-xwin lays them out (run `cargo xwin build --target x86_64-pc-windows-msvc` once).
set -euo pipefail

UPSTREAM="https://github.com/JetBrains/skiko.git"
REVISION="9a5b398bb2044fff7e7a84fbfd6f4b803e4427c0"
PUBLISHED_AS="0.144.6"
SKIA_RELEASE="m144-22f58c9fd4"
SKIA_URL="https://github.com/JetBrains/skia/releases/download/$SKIA_RELEASE/Skia-$SKIA_RELEASE-windows-Release-x64.zip"
CENTRAL="https://repo1.maven.org/maven2/org/jetbrains/skiko/skiko/$PUBLISHED_AS"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

die() {
    echo "error: $1" >&2
    shift
    for line in "$@"; do echo "       $line" >&2; done
    exit 1
}

[[ $# -ge 1 ]] || die "no work directory given" "usage: build-skiko-mingw.sh <work-dir> [--clean]"
WORK="$1"
shift
clean=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --clean) clean=1; shift ;;
        *) die "unknown argument '$1'" "usage: build-skiko-mingw.sh <work-dir> [--clean]" ;;
    esac
done

[[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]] ||
    die "JAVA_HOME does not name a JDK" "skiko's Gradle build needs a JDK 17 or 21."

llvm_bin=""
for candidate in $(ls -d "$HOME"/.konan/dependencies/llvm-*-essentials*/bin 2>/dev/null | sort -V -r); do
    if [[ -x "$candidate/clang" && -x "$candidate/llvm-ar" ]]; then llvm_bin="$candidate"; break; fi
done
[[ -n "$llvm_bin" ]] || die "no Kotlin/Native LLVM with clang and llvm-ar under ~/.konan/dependencies" \
    "Build any Kotlin/Native target once and Kotlin/Native downloads it."

xwin="${XWIN_DIR:-}"
if [[ -z "$xwin" ]]; then
    for candidate in "$HOME/Library/Caches/cargo-xwin/xwin" "$HOME/.cache/cargo-xwin/xwin"; do
        [[ -d "$candidate/crt/include" ]] && { xwin="$candidate"; break; }
    done
fi
[[ -n "$xwin" && -d "$xwin/sdk/include/ucrt" ]] || die "no MSVC runtime and Windows SDK headers found" \
    "Run 'cargo xwin build --target x86_64-pc-windows-msvc' once, or set XWIN_DIR."

[[ $clean -eq 1 ]] && rm -rf "$WORK"
mkdir -p "$WORK"

# 1. skiko at the pin, patched, reset every run so nobody's edits leak into a build.
checkout="$WORK/skiko"
[[ -d "$checkout/.git" ]] || git clone --quiet --no-checkout "$UPSTREAM" "$checkout"
git -C "$checkout" cat-file -e "$REVISION^{commit}" 2>/dev/null || git -C "$checkout" fetch --quiet origin "$REVISION"
git -C "$checkout" -c advice.detachedHead=false checkout --quiet --force "$REVISION"
git -C "$checkout" clean --quiet -fdx -e skiko/build -e skiko/dependencies -e .gradle
for patch in "$HERE"/*.patch; do
    git -C "$checkout" apply --whitespace=nowarn "$patch" ||
        die "$(basename "$patch") does not apply to skiko $REVISION"
done

# The Kotlin half. deploy.release keeps skiko from appending -SNAPSHOT. Only the mingwX64
# publication: publishing the root under the same version would replace JetBrains' metadata
# for every other target with one that knows only this one.
(
    cd "$checkout/skiko"
    ./gradlew --no-daemon --quiet publishMingwX64PublicationToMavenLocal \
        -Pskiko.native.mingw.enabled=true -Pskiko.awt.enabled=false \
        -Pdeploy.version="$PUBLISHED_AS" -Pdeploy.release=true
)

# The root, JetBrains' as published, with mingw_x64 added.
root="$HOME/.m2/repository/org/jetbrains/skiko/skiko/$PUBLISHED_AS"
mkdir -p "$root"
for suffix in .module .pom .jar -sources.jar -kotlin-tooling-metadata.json; do
    file="skiko-$PUBLISHED_AS$suffix"
    curl -fsSL -o "$root/$file.download" "$CENTRAL/$file" || die "could not download $CENTRAL/$file"
    mv "$root/$file.download" "$root/$file"
done
python3 "$HERE/extend-skiko-root.py" "$root/skiko-$PUBLISHED_AS.module" "$PUBLISHED_AS"

# 2. Skia for Windows, as JetBrains builds it.
skia="$WORK/skia-$SKIA_RELEASE-windows-x64"
if [[ ! -f "$skia/out/Release-windows-x64/skia.lib" ]]; then
    rm -rf "$skia" && mkdir -p "$skia"
    curl -fsSL -o "$skia.zip" "$SKIA_URL" || die "could not download $SKIA_URL"
    unzip -q -o "$skia.zip" -d "$skia"
    rm -f "$skia.zip"
fi
skia_out="$skia/out/Release-windows-x64"

# The C++ half, with skiko's own Windows defines (CommonTasksConfiguration.kt in the skiko
# checkout) and its native-target flags (no RTTI, no exceptions) in MSVC spelling.
out="$WORK/out/windows-x64"
obj="$WORK/obj"
rm -rf "$out" "$obj"
mkdir -p "$out" "$obj"
src="$checkout/skiko/src"
flags=(
    --driver-mode=cl --target=x86_64-pc-windows-msvc /std:c++17 /O2 /MT /GR- /c /nologo
    -Wno-everything
    -imsvc "$xwin/crt/include" -imsvc "$xwin/sdk/include/ucrt"
    -imsvc "$xwin/sdk/include/um" -imsvc "$xwin/sdk/include/shared"
    /DSK_ALLOW_STATIC_GLOBAL_INITIALIZERS=1 /DSK_FORCE_DISTANCE_FIELD_TEXT=0 /DSK_GAMMA_APPLY_TO_A8
    /DSK_GAMMA_SRGB /DSK_SCALAR_TO_FLOAT_EXCLUDED /DSK_SUPPORT_GPU=1 /DSK_GANESH /DSK_GL
    /DSK_SHAPER_HARFBUZZ_AVAILABLE /DSK_UNICODE_AVAILABLE /DSK_SHAPER_UNICODE_AVAILABLE
    /DSK_SUPPORT_OPENCL=0 /DSK_USING_THIRD_PARTY_ICU
    /DU_DISABLE_RENAMING=0 /DU_DISABLE_VERSION_SUFFIX=1 /DU_HAVE_LIB_SUFFIX=1 /DU_LIB_SUFFIX_C_NAME=_skiko
    /USK_HIDE_PATH_EDIT_METHODS
    /DSK_BUILD_FOR_WIN /D_CRT_SECURE_NO_WARNINGS /D_HAS_EXCEPTIONS=0 /DWIN32_LEAN_AND_MEAN /DNOMINMAX
    /DSK_DIRECT3D /DSK_ANGLE /DSK_RELEASE
)
for dir in "" include include/core include/gpu include/effects include/pathops include/utils \
           include/codec include/svg modules/jsonreader modules/skottie/include \
           modules/skparagraph/include modules/skshaper/include modules/skunicode/include \
           modules/sksg/include modules/svg/include third_party/externals/harfbuzz/src \
           third_party/icu third_party/externals/icu/source/common; do
    flags+=("/I$skia/$dir")
done
flags+=("/I$src/nativeJsMain/cpp" "/I$src/commonMain/cpp/common/include" "/I$src/commonMain/cpp/common")

compile() {
    local file="$1" name
    name="$(echo "${file#"$src"/}" | tr '/' '_')"
    "$llvm_bin/clang" "${flags[@]}" "$file" "/Fo$obj/${name%.*}.obj" ||
        die "skiko's $file did not compile in MSVC mode"
}
count=0
while IFS= read -r -d '' file; do
    compile "$file" &
    count=$((count + 1))
    # Four at a time: quick enough, and leaves the machine usable.
    (( count % 4 == 0 )) && wait
done < <(find "$src/commonMain/cpp/common" "$src/nativeJsMain/cpp" -type f \( -name '*.cc' -o -name '*.cpp' \) -print0)
wait
built=$(ls "$obj"/*.obj | wc -l | tr -d ' ')
[[ "$built" -eq "$count" ]] || die "compiled $built of skiko's $count C++ files"
"$llvm_bin/llvm-ar" rcs "$out/skiko-bridges.lib" "$obj"/*.obj

mkdir -p "$out/skia"
cp "$skia_out"/*.lib "$out/skia/"
cp "$skia_out/icudtl.dat" "$out/skia/"
ln -sfn "$skia" "$out/skia-include"

echo "skiko $PUBLISHED_AS for mingwX64 published to the local Maven repository"
echo "C++ half and Skia in $out ($count bridge sources)"
