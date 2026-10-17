#!/usr/bin/env bash
# Tests republish-skiko-mingw.py against a made-up repository, with no build.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
script="$(cd "$here/.." && pwd)/republish-skiko-mingw.py"
work="$(mktemp -d "${RUNNER_TEMP:-$here}/republish-skiko-mingw.XXXXXX")"
trap 'rm -rf "$work"' EXIT
failures=0
fail() { echo "  FAIL: $1" >&2; failures=$((failures + 1)); }

repo="$work/m2"
up="$repo/org/jetbrains/skiko/skiko-mingwx64/0.144.6"
mkdir -p "$up"
echo klib > "$up/skiko-mingwx64-0.144.6.klib"
echo sha > "$up/skiko-mingwx64-0.144.6.klib.sha1"
cat > "$up/skiko-mingwx64-0.144.6.module" <<'JSON'
{"component":{"url":"../../skiko/0.144.6/skiko-0.144.6.module","group":"org.jetbrains.skiko","module":"skiko","version":"0.144.6"},
 "variants":[{"name":"mingwX64ApiElements-published","files":[{"name":"skiko-mingwx64-0.144.6.klib","url":"skiko-mingwx64-0.144.6.klib"}]}]}
JSON
cat > "$up/skiko-mingwx64-0.144.6.pom" <<'XML'
<project><groupId>org.jetbrains.skiko</groupId><artifactId>skiko-mingwx64</artifactId><version>0.144.6</version></project>
XML
ui="$repo/org/thisisthepy/compose/ui/ui-graphics-mingwx64/1.11.1-ext.1"
mkdir -p "$ui"
cat > "$ui/ui-graphics-mingwx64-1.11.1-ext.1.module" <<'JSON'
{"variants":[{"name":"mingwX64ApiElements-published","dependencies":[
  {"group":"org.jetbrains.skiko","module":"skiko","version":{"requires":"0.144.6"}},
  {"group":"org.thisisthepy.compose.ui","module":"ui-util","version":{"requires":"1.11.1-ext.1"}}]}]}
JSON
cat > "$ui/ui-graphics-mingwx64-1.11.1-ext.1.pom" <<'XML'
<project><dependencies><dependency>
  <groupId>org.jetbrains.skiko</groupId>
  <artifactId>skiko</artifactId>
  <version>0.144.6</version>
</dependency></dependencies></project>
XML
desktop="$repo/org/thisisthepy/compose/ui/ui-graphics-desktop/1.11.1-ext.1"
mkdir -p "$desktop"
echo '{"variants":[{"name":"a","dependencies":[{"group":"org.jetbrains.skiko","module":"skiko","version":{"requires":"0.144.6"}}]}]}' \
    > "$desktop/ui-graphics-desktop-1.11.1-ext.1.module"
cpp="$work/cpp"
mkdir -p "$cpp/skia"
echo bridges > "$cpp/skiko-bridges.lib"
echo icu > "$cpp/embedded_icu.obj"
echo skia > "$cpp/skia/skia.lib"
echo data > "$cpp/skia/icudtl.dat"

echo "republish-skiko-mingw.py"
python3 -I "$script" "$repo" 0.144.6 1.11.1-ext.1 "$cpp" > /dev/null || fail "a complete input is refused"

out="$repo/org/thisisthepy/compose/skiko/skiko-mingwx64/1.11.1-ext.1"
[[ -f "$out/skiko-mingwx64-1.11.1-ext.1.klib" ]] || fail "the klib is not renamed"
[[ ! -e "$out/skiko-mingwx64-1.11.1-ext.1.klib.sha1" ]] || fail "a checksum of the old file is carried over"
grep -q '<groupId>org.thisisthepy.compose.skiko</groupId>' "$out/skiko-mingwx64-1.11.1-ext.1.pom" || fail "the pom keeps JetBrains' group"
grep -q 'Upstream version: 0.144.6' "$out/skiko-mingwx64-1.11.1-ext.1.pom" || fail "the pom does not name the upstream version"
python3 -I - "$out/skiko-mingwx64-1.11.1-ext.1.module" <<'PY' || fail "the module is not its own component"
import json, sys
m = json.load(open(sys.argv[1]))
assert m["component"] == {"group": "org.thisisthepy.compose.skiko", "module": "skiko-mingwx64", "version": "1.11.1-ext.1"}, m["component"]
assert m["variants"][0]["files"][0]["url"] == "skiko-mingwx64-1.11.1-ext.1.klib"
PY
python3 -I - "$out/skiko-mingwx64-1.11.1-ext.1-msvc.zip" <<'PY' || fail "the msvc zip is not laid out for the link"
import sys, zipfile
z = zipfile.ZipFile(sys.argv[1])
names = set(z.namelist())
assert {"obj/embedded_icu.obj", "lib/skiko-bridges.lib", "lib/skia.lib", "msvc-link.properties"} <= names, names
assert not any(n.endswith(".dat") or n.startswith("runtime/") for n in names), names
props = z.read("msvc-link.properties").decode()
assert "cppStandardLibrary=true" in props and "opengl32.lib" in props, props
PY
python3 -I - "$ui/ui-graphics-mingwx64-1.11.1-ext.1.module" <<'PY' || fail "the mingwX64 module still names JetBrains' skiko"
import json, sys
deps = json.load(open(sys.argv[1]))["variants"][0]["dependencies"]
assert deps[0] == {"group": "org.thisisthepy.compose.skiko", "module": "skiko-mingwx64", "version": {"requires": "1.11.1-ext.1"}}, deps[0]
assert deps[1]["module"] == "ui-util"
PY
grep -q '<artifactId>skiko-mingwx64</artifactId>' "$ui/ui-graphics-mingwx64-1.11.1-ext.1.pom" || fail "the mingwX64 pom still names JetBrains' skiko"
grep -q '"org.jetbrains.skiko"' "$desktop/ui-graphics-desktop-1.11.1-ext.1.module" || fail "a desktop module was changed"

rm -f "$cpp/embedded_icu.obj"
python3 -I "$script" "$repo" 0.144.6 1.11.1-ext.1 "$cpp" > /dev/null 2>&1 && fail "a missing embedded ICU object is not refused"
echo icu > "$cpp/embedded_icu.obj"
rm -rf "$cpp/skiko-bridges.lib"
python3 -I "$script" "$repo" 0.144.6 1.11.1-ext.1 "$cpp" > /dev/null 2>&1 && fail "a missing C++ half is not refused"

if [[ $failures -gt 0 ]]; then echo "$failures failure(s)"; exit 1; fi
echo "ok"
