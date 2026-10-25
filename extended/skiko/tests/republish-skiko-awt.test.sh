#!/usr/bin/env bash
# Tests republish-skiko-awt.py and repoint-desktop-skiko.py against a made-up repository, with no build.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
skiko="$(cd "$here/.." && pwd)"
work="$(mktemp -d "${RUNNER_TEMP:-$here}/republish-skiko-awt.XXXXXX")"
trap 'rm -rf "$work"' EXIT
failures=0
fail() { echo "  FAIL: $1" >&2; failures=$((failures + 1)); }
V=1.11.1-ext.2

up="$work/up"
mkdir -p "$up"
echo jar > "$up/skiko-awt-0.144.6.jar"
echo sha > "$up/skiko-awt-0.144.6.jar.sha1"
cat > "$up/skiko-awt-0.144.6.pom" <<'XML'
<project><groupId>org.jetbrains.skiko</groupId><artifactId>skiko-awt</artifactId><version>0.144.6</version></project>
XML
cat > "$up/skiko-awt-0.144.6.module" <<'JSON'
{"component":{"url":"../../skiko/0.144.6/skiko-0.144.6.module","group":"org.jetbrains.skiko","module":"skiko-awt","version":"0.144.6"},
 "variants":[
  {"name":"awtApiElements-published","files":[{"name":"skiko-awt-0.144.6.jar","url":"skiko-awt-0.144.6.jar"}],
   "capabilities":[{"group":"org.jetbrains.skiko","name":"skiko-awt","version":"0.144.6"}]},
  {"name":"awtRuntimeElements-published","files":[{"name":"skiko-awt-0.144.6.jar","url":"skiko-awt-0.144.6.jar"}],
   "dependencies":[{"group":"org.jetbrains.skiko","module":"skiko-awt-runtime-macos-arm64","version":{"requires":"0.144.6"}}]}]}
JSON

echo "republish-skiko-awt.py"
out="$work/out"
python3 -I "$skiko/republish-skiko-awt.py" "$up" "$out" 0.144.6 org.thisisthepy.compose.skiko $V || fail "a complete input is refused"
python3 -I - "$out/skiko-awt-$V.module" <<'PY' || fail "every variant must declare exactly its own capability"
import json, sys
m = json.load(open(sys.argv[1]))
assert m["component"] == {"group": "org.thisisthepy.compose.skiko", "module": "skiko-awt", "version": "1.11.1-ext.2"}, m["component"]
assert len(m["variants"]) == 2
own = [{"group": "org.thisisthepy.compose.skiko", "name": "skiko-awt", "version": "1.11.1-ext.2"}]
for v in m["variants"]:
    assert v["capabilities"] == own, v
runtime = m["variants"][1]["dependencies"][0]
assert runtime["group"] == "org.jetbrains.skiko" and runtime["version"]["requires"] == "0.144.6", runtime
PY
python3 -I "$skiko/republish-skiko-awt.py" "$up" "$work/x" 0.144.6 g $V extra > /dev/null 2>&1 && fail "the retired capability-version argument is accepted"

echo "repoint-desktop-skiko.py"
repo="$work/m2"
ui="$repo/org/thisisthepy/compose/ui/ui-desktop/$V"
native="$repo/org/thisisthepy/compose/ui/ui-linuxx64/$V"
mkdir -p "$ui" "$native"
cat > "$ui/ui-desktop-$V.module" <<JSON
{"variants":[{"name":"desktopApiElements-published","dependencies":[
  {"group":"org.jetbrains.skiko","module":"skiko","version":{"requires":"0.144.6"}},
  {"group":"org.thisisthepy.compose.ui","module":"ui-util","version":{"requires":"$V"}}]}]}
JSON
cat > "$ui/ui-desktop-$V.pom" <<XML
<project><dependencies><dependency>
  <groupId>org.jetbrains.skiko</groupId>
  <artifactId>skiko</artifactId>
  <version>0.144.6</version>
</dependency></dependencies></project>
XML
cp "$ui/ui-desktop-$V.module" "$native/ui-linuxx64-$V.module"
cp "$ui/ui-desktop-$V.pom" "$native/ui-linuxx64-$V.pom"
python3 -I "$skiko/repoint-desktop-skiko.py" "$repo" --require > /dev/null || fail "a repository with a desktop module is refused"
python3 -I - "$ui/ui-desktop-$V.module" <<'PY' || fail "the desktop module must depend on the fork's skiko-awt"
import json, sys
deps = json.load(open(sys.argv[1]))["variants"][0]["dependencies"]
assert deps[0] == {"group": "org.thisisthepy.compose.skiko", "module": "skiko-awt", "version": {"requires": "1.11.1-ext.2"}}, deps[0]
assert deps[1]["module"] == "ui-util"
PY
python3 -I - "$ui/ui-desktop-$V.pom" <<'PY' || fail "the desktop pom must depend on the fork's skiko-awt"
import re, sys
t = open(sys.argv[1]).read()
assert "org.jetbrains.skiko" not in t, t
assert re.search(r"<groupId>org.thisisthepy.compose.skiko</groupId>\s*<artifactId>skiko-awt</artifactId>\s*<version>1.11.1-ext.2</version>", t), t
PY
grep -q '"org.jetbrains.skiko"' "$native/ui-linuxx64-$V.module" || fail "a Kotlin/Native module was changed"
grep -q '<artifactId>skiko</artifactId>' "$native/ui-linuxx64-$V.pom" || fail "a Kotlin/Native pom was changed"
python3 -I "$skiko/repoint-desktop-skiko.py" "$work/empty" --require > /dev/null 2>&1 && fail "--require accepts a repository with no desktop module"

if [[ $failures -gt 0 ]]; then echo "$failures failure(s)"; exit 1; fi
echo "ok"
