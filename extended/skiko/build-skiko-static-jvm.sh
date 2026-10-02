#!/usr/bin/env bash
# Builds skiko's JVM native library as a static archive, for a GraalVM native image that
# links Skia in rather than loading it from a file.
#
# skiko publishes its JVM natives only as a shared library inside a jar, which the desktop
# loader unpacks and opens by absolute path. A single executable cannot carry a second file,
# so this archives the same objects that shared library is linked from: skiko's C++ bindings,
# its Objective-C ones on macOS, and the prebuilt Skia skiko's build downloads. Nothing in
# skiko's build is modified; its compile tasks run and their outputs are archived.
#
# One object is replaced. skiko's jawt.cc opens <java.home>/lib/libjawt.<ext> by path to
# find JAWT_GetAWT, and a native image has no java.home. In this archive Skiko_GetAWT calls
# JAWT_GetAWT directly instead (static_jawt.c), and the image links JAWT's own archive.
#
# Usage: build-skiko-static-jvm.sh <work-dir>
#
# Output, in <work-dir>/out/<os>-<arch>/:
#   libskiko-static.a   skiko's own bindings (skiko-static.lib on Windows). Link the whole
#                       archive (-force_load, --whole-archive, /WHOLEARCHIVE): a JNI entry point is
#                       reached by name and nothing refers to it by symbol, so ordinary
#                       archive semantics would drop every one of them.
#   skia/*.a, *.lib     Skia, as JetBrains builds it. Link these as ordinary archives, never
#                       forced: its module archives (skottie, sksg, svg) each carry their own
#                       copy of Skia's core objects, and forcing them in defines those twice.
#
# macOS arm64, Linux x64 (g++, ar, and the X11, GL, fontconfig and dbus development
# headers skiko compiles against), and Windows x64 run from Git Bash with the MSVC tools on PATH (a Developer
# prompt, or ilammy/msvc-dev-cmd on CI). Needs git and a JDK 17 or 21 in JAVA_HOME; the JDK's
# headers are what static_jawt.c compiles against.
set -euo pipefail

UPSTREAM="https://github.com/JetBrains/skiko.git"
REVISION="9a5b398bb2044fff7e7a84fbfd6f4b803e4427c0"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

die() {
    echo "error: $1" >&2
    shift
    for line in "$@"; do echo "       $line" >&2; done
    exit 1
}

[[ $# -eq 1 ]] || die "usage: build-skiko-static-jvm.sh <work-dir>"
WORK="$1"

case "$(uname -s)-$(uname -m)" in
    Darwin-arm64)
        host="macos"
        tasks=("compileJvmBindingsMacosArm64" "objcCompileMacosArm64")
        object_dirs=("compile/Release-macos-jvm-arm64" "compileObjC/Release-macos-arm64")
        skia_glob="*-macos-Release-arm64"
        platform="macos-arm64"
        ;;
    Linux-x86_64)
        host="linux"
        tasks=("compileJvmBindingsLinuxX64")
        object_dirs=("compile/Release-linux-jvm-x64")
        skia_glob="*-linux-Release-x64"
        platform="linux-x64"
        ;;
    MINGW*-x86_64|MSYS*-x86_64)
        host="windows"
        tasks=("compileJvmBindingsWindowsX64")
        object_dirs=("compile/Release-windows-jvm-x64")
        skia_glob="*-windows-Release-x64"
        platform="windows-x64"
        ;;
    *) die "this builds macOS arm64, Linux x64 and Windows x64 only so far (this is $(uname -s) $(uname -m))" ;;
esac

[[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]] ||
    die "JAVA_HOME does not name a JDK" "skiko's Gradle build needs a JDK 17 or 21."

mkdir -p "$WORK"
checkout="$WORK/skiko"
[[ -d "$checkout/.git" ]] || git clone --quiet --no-checkout "$UPSTREAM" "$checkout"
# skiko has test screenshots whose names pass Windows' 260 character limit once this work
# directory is prefixed. GitHub's Windows runners allow long paths globally; a developer's
# git may not, and the operating system's own setting is not enough for git.
if [[ "$host" == "windows" ]]; then git -C "$checkout" config core.longpaths true; fi
git -C "$checkout" cat-file -e "$REVISION^{commit}" 2>/dev/null || git -C "$checkout" fetch --quiet origin "$REVISION"
git -C "$checkout" -c advice.detachedHead=false checkout --quiet --force "$REVISION"

echo "==> compiling skiko's JVM bindings for $platform (Skia is downloaded prebuilt)"
(
    cd "$checkout/skiko"
    ./gradlew --no-daemon --quiet "${tasks[@]}"
)

out="$WORK/out/$platform"
mkdir -p "$out"

# The JAWT getter that calls the linked-in JAWT directly, compiled against the JDK's headers.
# On Windows with the static C runtime, as JetBrains' Skia is.
if [[ "$host" == "macos" ]]; then
    "${CC:-cc}" -c -O2 -arch arm64 -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/darwin" \
        "$HERE/static_jawt.c" -o "$out/static_jawt.o"
    jawt_object="$out/static_jawt.o"
    suffix="o"
elif [[ "$host" == "linux" ]]; then
    "${CC:-cc}" -c -O2 -fPIC -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
        "$HERE/static_jawt.c" -o "$out/static_jawt.o"
    jawt_object="$out/static_jawt.o"
    suffix="o"
else
    cl.exe //nologo //O2 //MT //c "//I$(cygpath -w "$JAVA_HOME/include")" "//I$(cygpath -w "$JAVA_HOME/include/win32")" \
        "$(cygpath -w "$HERE/static_jawt.c")" "//Fo$(cygpath -w "$out/static_jawt.obj")"
    jawt_object="$out/static_jawt.obj"
    # skiko compiles with clang-cl on Windows and names its objects .o there too.
    suffix="o"
fi

objects=()
for dir in "${object_dirs[@]}"; do
    while IFS= read -r -d '' file; do
        objects+=("$file")
    done < <(find "$checkout/skiko/build/out/$dir" -name "*.$suffix" ! -name "jawt.$suffix" -print0)
done
[[ ${#objects[@]} -gt 0 ]] || die "skiko's compile tasks produced no objects"

skia="$(find "$checkout/skiko/dependencies/skia" -type d -name "$skia_glob" -print -quit)"
[[ -n "$skia" ]] || die "no unpacked Skia under $checkout/skiko/dependencies/skia"
rm -rf "$out/skia" && mkdir -p "$out/skia"
# JetBrains names Skia's archives .a on macOS and .lib on Windows.
skia_archives=()
while IFS= read -r -d '' file; do
    skia_archives+=("$file")
done < <(find "$skia/out" -mindepth 2 -maxdepth 2 \( -name "*.a" -o -name "*.lib" \) -print0)
[[ ${#skia_archives[@]} -gt 0 ]] || die "no Skia archives under $skia/out"
cp "${skia_archives[@]}" "$out/skia/"

if [[ "$host" == "macos" ]]; then
    archive="$out/libskiko-static.a"
    rm -f "$archive"
    libtool -static -o "$archive" "${objects[@]}" "$jawt_object" 2>/dev/null
    nm -g "$archive" > "$out/symbols.txt" 2>/dev/null || true
    prefix="_"
elif [[ "$host" == "linux" ]]; then
    # skiko and AWT's libawt each define a global `JavaVM *jvm`, set to the same VM by their
    # JNI_OnLoad. Linked into one executable they collide; skiko's is made weak, so the two
    # are one variable, which AWT's initialisation sets before skiko draws.
    staged="$out/objects"
    rm -rf "$staged" && mkdir -p "$staged"
    linked_objects=()
    index=0
    for object in "${objects[@]}"; do
        copy="$staged/$index-$(basename "$object")"
        objcopy --weaken-symbol=jvm "$object" "$copy"
        linked_objects+=("$copy")
        index=$((index + 1))
    done
    archive="$out/libskiko-static.a"
    rm -f "$archive"
    ar rcs "$archive" "${linked_objects[@]}" "$jawt_object"
    rm -rf "$staged"
    nm -g --defined-only "$archive" > "$out/symbols.txt" 2>/dev/null || true
    prefix=""
else
    archive="$out/skiko-static.lib"
    rm -f "$archive"
    response="$out/objects.rsp"
    : > "$response"
    for object in "${objects[@]}" "$jawt_object"; do echo "\"$(cygpath -w "$object")\"" >> "$response"; done
    lib.exe //nologo "//OUT:$(cygpath -w "$archive")" "@$(cygpath -w "$response")"
    dumpbin.exe //nologo //linkermember:1 "$(cygpath -w "$archive")" | awk 'NF == 2 {print "0 T " $2}' > "$out/symbols.txt"
    prefix=""
fi
entry_points="$(grep -c " T ${prefix}Java_org_jetbrains_ski" "$out/symbols.txt" || true)"
[[ "$entry_points" -gt 0 ]] || die "the archive has no JNI entry points in it"
grep -q " T ${prefix}Skiko_GetAWT\$" "$out/symbols.txt" || die "the archive has no Skiko_GetAWT"

echo "$archive: $(wc -c < "$archive" | tr -d ' ') bytes, $entry_points JNI entry points"
