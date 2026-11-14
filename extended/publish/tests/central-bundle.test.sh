#!/usr/bin/env bash
# Tests central_bundle.py on a small Maven repository written here: what a complete bundle
# looks like passes, and each gap Central refuses (or a consumer would trip on) fails with
# its own message.
#
# Usage: extended/publish/tests/central-bundle.test.sh   (needs python3 and gpg)
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
tool="$here/../central_bundle.py"
work="$(mktemp -d "${RUNNER_TEMP:-$here}/cbt.XXXXXX")"
# gpg-agent's socket lives in GNUPGHOME, and an absolute path deep in a checkout can be
# longer than a socket name may be; a relative one from inside the work directory is not.
cd "$work"
export GNUPGHOME=gnupg
trap 'gpgconf --kill gpg-agent 2>/dev/null || true; rm -rf "$work"' EXIT
"$here/../throwaway-key.sh" "$GNUPGHOME" > /dev/null

failures=0
pass() { echo "ok    $1"; }
fail() { echo "FAIL  $1" >&2; failures=$((failures + 1)); }

V=1.11.1-ext.1
MARKER="do_not_remove: published-with-gradle-metadata"
pom() { # <file> <group> <artifact> <version> [packaging] [extra xml]
    cat > "$1" <<XML
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
  <modelVersion>4.0.0</modelVersion>
  <groupId>$2</groupId>
  <artifactId>$3</artifactId>
  <version>$4</version>
  <packaging>${5:-jar}</packaging>
  ${6:-}
</project>
XML
}
jar() { # <file> [entry]
    python3 -I - "$1" "${2:-}" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], "w") as z:
    z.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n\r\n")
    if sys.argv[2]:
        z.writestr(sys.argv[2], "implementation-class=Example\n")
PY
}

# A repository like the ones the builds leave: a KMP root whose module points at a target,
# the target with a klib, a Gradle plugin with its marker, and something outside the namespace
# that must not be staged.
make_repo() {
    local repo="$1" d
    d="$repo/org/thisisthepy/compose/ui/ui/$V"; mkdir -p "$d"
    pom "$d/ui-$V.pom" org.thisisthepy.compose.ui ui "$V" jar "<!-- $MARKER -->"
    jar "$d/ui-$V.jar"
    cat > "$d/ui-$V.module" <<JSON
{"formatVersion":"1.1","component":{"group":"org.thisisthepy.compose.ui","module":"ui","version":"$V"},
 "variants":[{"name":"linuxX64ApiElements-published","available-at":{"url":"../../ui-linuxx64/$V/ui-linuxx64-$V.module","group":"org.thisisthepy.compose.ui","module":"ui-linuxx64","version":"$V"}},
             {"name":"metadataApiElements","files":[{"name":"ui-$V.jar","url":"ui-$V.jar"}]}]}
JSON
    d="$repo/org/thisisthepy/compose/ui/ui-linuxx64/$V"; mkdir -p "$d"
    pom "$d/ui-linuxx64-$V.pom" org.thisisthepy.compose.ui ui-linuxx64 "$V" klib \
        "<dependencies><dependency><groupId>org.thisisthepy.compose.ui</groupId><artifactId>ui</artifactId><version>$V</version></dependency></dependencies>"
    echo klib > "$d/ui-linuxx64-$V.klib"
    jar "$d/ui-linuxx64-$V-sources.jar"
    d="$repo/org/thisisthepy/compose/compose-gradle-plugin/$V"; mkdir -p "$d"
    pom "$d/compose-gradle-plugin-$V.pom" org.thisisthepy.compose compose-gradle-plugin "$V"
    jar "$d/compose-gradle-plugin-$V.jar" META-INF/gradle-plugins/org.thisisthepy.compose.properties
    d="$repo/org/thisisthepy/compose/org.thisisthepy.compose.gradle.plugin/$V"; mkdir -p "$d"
    pom "$d/org.thisisthepy.compose.gradle.plugin-$V.pom" org.thisisthepy.compose org.thisisthepy.compose.gradle.plugin "$V" pom
    d="$repo/org/jetbrains/skiko/skiko-awt/0.144.6.1"; mkdir -p "$d"
    pom "$d/skiko-awt-0.144.6.1.pom" org.jetbrains.skiko skiko-awt 0.144.6.1
    echo junk > "$repo/org/thisisthepy/compose/ui/ui/maven-metadata-local.xml"
}

build() { # <name> -> stages, signs; prints the staging dir
    local repo="$work/$1-repo" out="$work/$1-out"
    rm -rf "$repo" "$out"
    make_repo "$repo"
    python3 -I "$tool" stage --from "$repo" --out "$out" --project thisisthepy/example > "$work/$1-stage.log"
    python3 -I "$tool" sign "$out" > /dev/null
    echo "$out"
}

expect_fail() { # <description> <pattern> <dir> [validate args]
    local description="$1" pattern="$2" out="$3"; shift 3
    if python3 -I "$tool" validate "$out" "$@" > "$work/log" 2>&1; then
        fail "$description: validate passed"; cat "$work/log" >&2
    elif grep -q -- "$pattern" "$work/log"; then
        pass "$description"
    else
        fail "$description: no '$pattern' in"; cat "$work/log" >&2
    fi
}

out="$(build good)"
if python3 -I "$tool" validate "$out" --version "$V" --require org.thisisthepy.compose.ui:ui --verify-signatures > "$work/log"; then
    pass "a complete bundle validates, signatures included"
else
    fail "a complete bundle does not validate"; cat "$work/log" >&2
fi
[[ ! -e "$out/org/jetbrains" ]] && pass "nothing outside org.thisisthepy is staged" || fail "org.jetbrains was staged"
[[ ! -e "$out/org/thisisthepy/compose/ui/ui/maven-metadata-local.xml" ]] && pass "local repository metadata is left out" || fail "maven-metadata-local.xml was staged"
grep -q "<scm>" "$out/org/thisisthepy/compose/ui/ui/$V/ui-$V.pom" && pass "the POM gets scm" || fail "no scm in the POM"
grep -q "empty org/thisisthepy/compose/ui/ui-linuxx64/$V/ui-linuxx64-$V-javadoc.jar" "$work/good-stage.log" &&
    pass "an empty javadoc jar is added and reported" || fail "the empty javadoc jar is not reported"
grep -q "ui-linuxx64-$V-sources.jar" "$work/good-stage.log" && fail "an existing sources jar was replaced" || pass "an existing sources jar is kept"
python3 -I "$tool" zip "$out" "$work/bundle.zip" > /dev/null
python3 -I -c 'import sys, zipfile; n = zipfile.ZipFile(sys.argv[1]).namelist(); sys.exit(0 if any(x.startswith("org/thisisthepy/compose/ui/ui/") and x.endswith(".pom.asc") for x in n) else 1)' "$work/bundle.zip" &&
    pass "the bundle zip is in Maven layout" || fail "the bundle zip layout"

grep -q -- "<!-- $MARKER -->" "$out/org/thisisthepy/compose/ui/ui/$V/ui-$V.pom" &&
    pass "the POM keeps the marker that sends Gradle to the .module" || fail "staging dropped the gradle-metadata marker"
expect_fail "the release version is enforced" "not 1.11.1-ext.2" "$out" --version 1.11.1-ext.2
expect_fail "a required coordinate is enforced" "required, and not in the bundle" "$out" --require org.thisisthepy.compose.skiko:skiko-awt

out="$(build nomarker)"; p="$out/org/thisisthepy/compose/ui/ui/$V/ui-$V.pom"
grep -v -- "$MARKER" "$p" > "$p.tmp" && mv "$p.tmp" "$p"
python3 -I "$tool" sign "$out" > /dev/null
expect_fail "a POM beside a .module without the gradle-metadata marker fails" "published-with-gradle-metadata" "$out"
out="$(build noasc)"; rm "$out/org/thisisthepy/compose/ui/ui/$V/ui-$V.jar.asc"
expect_fail "a missing signature fails" "has no .asc" "$out"
out="$(build nosha512)"; rm "$out/org/thisisthepy/compose/ui/ui/$V/ui-$V.pom.sha512"
expect_fail "a missing sha512 fails" "has no .sha512" "$out"
out="$(build badmd5)"; echo 0 > "$out/org/thisisthepy/compose/ui/ui/$V/ui-$V.jar.md5"
expect_fail "a wrong checksum fails" "md5 does not match" "$out"
out="$(build tampered)"; echo x >> "$out/org/thisisthepy/compose/ui/ui-linuxx64/$V/ui-linuxx64-$V.klib"
expect_fail "a signature that does not verify fails" "does not verify" "$out" --verify-signatures
out="$(build nojavadoc)"; rm "$out/org/thisisthepy/compose/ui/ui/$V/ui-$V-javadoc.jar"
expect_fail "a missing javadoc jar fails" "no javadoc jar" "$out"
out="$(build dangling)"; rm -r "$out/org/thisisthepy/compose/ui/ui-linuxx64"
expect_fail "a variant pointing at an unpublished target fails" "linuxX64ApiElements-published points at" "$out"
out="$(build nomarker)"; rm -r "$out/org/thisisthepy/compose/org.thisisthepy.compose.gradle.plugin"
expect_fail "a plugin without its marker fails" "marker" "$out"
out="$(build nodesc)"
p="$out/org/thisisthepy/compose/ui/ui/$V/ui-$V.pom"
python3 -I - "$p" <<'PY'
import re, sys
text = open(sys.argv[1]).read()
open(sys.argv[1], "w").write(re.sub(r"<description>.*?</description>", "", text, flags=re.S))
PY
expect_fail "a POM without a description fails" "no description" "$out"

# Capabilities: a variant may declare its own coordinate, and nothing else (Amper finds no
# variant otherwise). 1.11.1-ext.3's skiko-awt declared JetBrains' skiko-awt beside its own.
capabilities() { # <module file> <json list of [group, name]>
    python3 -I - "$1" "$2" <<'PY2'
import json, sys
path, names = sys.argv[1], json.loads(sys.argv[2])
m = json.load(open(path))
v = m["component"]["version"]
for variant in m["variants"]:
    variant["capabilities"] = [{"group": g, "name": n, "version": v} for g, n in names]
json.dump(m, open(path, "w"))
PY2
}
out="$(build owncap)"; m="$out/org/thisisthepy/compose/ui/ui/$V/ui-$V.module"
capabilities "$m" '[["org.thisisthepy.compose.ui", "ui"]]'
python3 -I "$tool" sign "$out" > /dev/null
python3 -I "$tool" validate "$out" > "$work/log" && pass "a variant declaring only its own capability validates" ||
    { fail "a variant declaring only its own capability"; cat "$work/log" >&2; }
out="$(build secondcap)"; m="$out/org/thisisthepy/compose/ui/ui/$V/ui-$V.module"
capabilities "$m" '[["org.thisisthepy.compose.ui", "ui"], ["org.jetbrains.skiko", "skiko-awt"]]'
python3 -I "$tool" sign "$out" > /dev/null
expect_fail "a second capability fails" "declares the capability org.jetbrains.skiko:skiko-awt besides its own" "$out"
out="$(build othercap)"; m="$out/org/thisisthepy/compose/ui/ui/$V/ui-$V.module"
capabilities "$m" '[["org.jetbrains.skiko", "skiko-awt"]]'
python3 -I "$tool" sign "$out" > /dev/null
expect_fail "a capability other than its own fails" "declares the capability org.jetbrains.skiko:skiko-awt" "$out"

# A desktop module must name the fork's skiko-awt, not JetBrains' skiko.
out="$(build desktopskiko)"
d="$out/org/thisisthepy/compose/ui/ui-desktop/$V"; mkdir -p "$d"
pom "$d/ui-desktop-$V.pom" org.thisisthepy.compose.ui ui-desktop "$V" jar "<!-- $MARKER -->"
jar "$d/ui-desktop-$V.jar"; jar "$d/ui-desktop-$V-sources.jar"; jar "$d/ui-desktop-$V-javadoc.jar"
cat > "$d/ui-desktop-$V.module" <<JSON
{"formatVersion":"1.1","component":{"group":"org.thisisthepy.compose.ui","module":"ui-desktop","version":"$V"},
 "variants":[{"name":"desktopRuntimeElements-published","files":[{"name":"ui-desktop-$V.jar","url":"ui-desktop-$V.jar"}],
   "dependencies":[{"group":"org.jetbrains.skiko","module":"skiko","version":{"requires":"0.144.6"}}]}]}
JSON
python3 -I "$tool" sign "$out" > /dev/null
expect_fail "a desktop module on JetBrains' skiko fails" "variant desktopRuntimeElements-published depends on JetBrains' org.jetbrains.skiko:skiko" "$out"
python3 -I "$here/../../skiko/repoint-desktop-skiko.py" "$out" --require > /dev/null
python3 -I "$tool" sign "$out" > /dev/null
# Added after staging, so its POM lacks what staging adds; only the skiko lines count here.
python3 -I "$tool" validate "$out" > "$work/log" || true
if grep -q "JetBrains' org.jetbrains.skiko" "$work/log" || ! grep -q "depends on org.thisisthepy.compose.skiko:skiko-awt:$V" "$work/log"; then
    fail "a repointed desktop module"; cat "$work/log" >&2
else
    pass "a desktop module repointed at the fork's skiko-awt passes the skiko check"
fi
out="$(build desktoppom)"
d="$out/org/thisisthepy/compose/ui/ui-desktop/$V"; mkdir -p "$d"
pom "$d/ui-desktop-$V.pom" org.thisisthepy.compose.ui ui-desktop "$V" jar \
    "<dependencies><dependency><groupId>org.jetbrains.skiko</groupId><artifactId>skiko-awt</artifactId><version>0.144.6</version></dependency></dependencies>"
jar "$d/ui-desktop-$V.jar"; jar "$d/ui-desktop-$V-sources.jar"; jar "$d/ui-desktop-$V-javadoc.jar"
python3 -I "$tool" sign "$out" > /dev/null
expect_fail "a desktop POM on JetBrains' skiko-awt fails" "the POM depends on JetBrains' org.jetbrains.skiko:skiko-awt" "$out"

# Class files: Java 11 (major 55) at most, outside CLASS_MAJOR_EXCEPTIONS. 1.11.1-ext.3's
# design systems were major 69 (Java 25), the window modules 65 (Java 21).
classjar() { # <file> <major> [entry prefix]
    python3 -I - "$1" "$2" "${3:-}" <<'PY2'
import io, sys, zipfile
path, major, prefix = sys.argv[1], int(sys.argv[2]), sys.argv[3]
cls = b"\xca\xfe\xba\xbe" + (0).to_bytes(2, "big") + major.to_bytes(2, "big") + b"\0" * 8
with zipfile.ZipFile(path, "w") as z:
    z.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n\r\n")
    z.writestr("example/Old.class", cls[:6] + (55).to_bytes(2, "big") + cls[8:])
    z.writestr("META-INF/versions/21/example/New.class", cls[:6] + (65).to_bytes(2, "big") + cls[8:])
    if prefix:
        inner = io.BytesIO()
        with zipfile.ZipFile(inner, "w") as i:
            i.writestr("example/Inner.class", cls)
        z.writestr(prefix, inner.getvalue())
    else:
        z.writestr("example/Example.class", cls)
PY2
}
majorjar() { # <name> <group dir> <artifact> <major> [ext] [nested jar entry]
    local out d ext="${5:-jar}"
    out="$(build "$1")"; d="$out/org/thisisthepy/compose/$2/$3/$V"; mkdir -p "$d"
    pom "$d/$3-$V.pom" "org.thisisthepy.compose.${2//\//.}" "$3" "$V" "$ext"
    classjar "$d/$3-$V.$ext" "$4" "${6:-}"; jar "$d/$3-$V-sources.jar"; jar "$d/$3-$V-javadoc.jar"
    python3 -I "$tool" sign "$out" > /dev/null
    echo "$out"
}
out="$(majorjar java11 designsystem designsystem-jvm 55)"
# Added after staging, so its POM lacks what staging adds; only the class-major lines count here.
python3 -I "$tool" validate "$out" > "$work/log" 2>&1 || true
grep -q "above major" "$work/log" &&
    { fail "a Java 11 jar (with a Java 21 multi-release class) is refused"; cat "$work/log" >&2; } ||
    pass "a Java 11 jar passes, its META-INF/versions classes left out"
out="$(majorjar java25 designsystem designsystem-jvm 69)"
expect_fail "a Java 25 jar fails" "designsystem-jvm-$V.jar has 1 class file(s) above major 55 (Java 11), up to major 69 (Java 25)" "$out"
out="$(majorjar java21 window scene-jvm 65)"
expect_fail "a Java 21 window jar fails" "scene-jvm-$V.jar has 1 class file(s) above major 55" "$out"
out="$(majorjar aar25 designsystem designsystem-android 69 aar libs/designsystem-jvm.jar)"
expect_fail "an aar carrying a Java 25 jar fails" "libs/designsystem-jvm.jar!/example/Inner.class" "$out"
out="$(majorjar graalvm56 window graalvm-macos 56)"
expect_fail "a GraalVM module above its allow-listed major fails" "graalvm-macos-$V.jar has 1 class file(s) above major 55" "$out"
out="$(majorjar linux61 window graalvm-linux 61)"
python3 -I "$tool" validate "$out" > "$work/log" 2>&1 || true
grep -q "above major" "$work/log" &&
    { fail "graalvm-linux at its allow-listed major 61 is refused"; cat "$work/log" >&2; } ||
    pass "graalvm-linux at its allow-listed major 61 (Java 17) passes"
out="$(majorjar linux65 window graalvm-linux 65)"
expect_fail "graalvm-linux above 61 fails" "graalvm-linux-$V.jar has 1 class file(s) above major 61 (Java 17)" "$out"
# The jar 1.11.1-ext.3 put on Central, when the network has it (CI does).
ext3="org/thisisthepy/compose/designsystem/designsystem-jvm/1.11.1-ext.3/designsystem-jvm-1.11.1-ext.3.jar"
if curl -sSfL --max-time 60 -o "$work/ext3.jar" "https://repo1.maven.org/maven2/$ext3" 2>/dev/null; then
    out="$(majorjar ext3 designsystem designsystem-jvm 55)"
    cp "$work/ext3.jar" "$out/org/thisisthepy/compose/designsystem/designsystem-jvm/$V/designsystem-jvm-$V.jar"
    python3 -I "$tool" sign "$out" > /dev/null
    expect_fail "1.11.1-ext.3's designsystem-jvm jar from Central fails" "up to major 69 (Java 25)" "$out"
else
    echo "skip  1.11.1-ext.3's designsystem-jvm jar (Maven Central unreachable)"
fi

for bad in 1.11.1-SNAPSHOT 1.11.1-ext.1-dev 0.1.0; do
    repo="$work/v-repo"; out="$work/v-out"; rm -rf "$repo" "$out"
    d="$repo/org/thisisthepy/compose/window/common/$bad"; mkdir -p "$d"
    pom "$d/common-$bad.pom" org.thisisthepy.compose.window common "$bad" pom
    python3 -I "$tool" stage --from "$repo" --out "$out" > /dev/null
    python3 -I "$tool" sign "$out" > /dev/null
    expect_fail "version $bad is refused" "common:$bad" "$out"
done

repo="$work/g-repo"; out="$work/g-out"; rm -rf "$repo" "$out"
d="$repo/org/thisisthepy/compose/window/common/$V"; mkdir -p "$d"
pom "$d/common-$V.pom" org.jetbrains.compose common "$V" pom
python3 -I "$tool" stage --from "$repo" --out "$out" > /dev/null
python3 -I "$tool" sign "$out" > /dev/null
expect_fail "a POM whose group is not its directory's fails" "groupId is 'org.jetbrains.compose'" "$out"

# Two repositories with the same file: the first one named wins.
a="$work/a-repo"; b="$work/b-repo"; out="$work/ab-out"; rm -rf "$a" "$b" "$out"
make_repo "$a"; make_repo "$b"
echo other > "$b/org/thisisthepy/compose/ui/ui-linuxx64/$V/ui-linuxx64-$V.klib"
python3 -I "$tool" stage --from "$a" --from "$b" --out "$out" > "$work/log"
[[ "$(cat "$out/org/thisisthepy/compose/ui/ui-linuxx64/$V/ui-linuxx64-$V.klib")" == klib ]] && grep -q "kept the copy" "$work/log" &&
    pass "the first repository wins a conflict, and it is reported" || fail "conflict handling"

# A root that lists a target the release does not build, and a constraint on a sibling the
# release does not publish: both are taken out at staging, and listed. A real dependency on a
# missing fork artifact is not, so validate still catches it.
repo="$work/prune-repo"; out="$work/prune-out"; rm -rf "$repo" "$out"
make_repo "$repo"
m="$repo/org/thisisthepy/compose/ui/ui/$V/ui-$V.module"
python3 -I - "$m" "$V" <<'PY2'
import json, sys
path, v = sys.argv[1:]
m = json.load(open(path))
m["variants"].append({"name": "iosArm64ApiElements-published", "available-at": {"url": f"../../ui-iosarm64/{v}/ui-iosarm64-{v}.module", "group": "org.thisisthepy.compose.ui", "module": "ui-iosarm64", "version": v}})
m["variants"][1]["dependencyConstraints"] = [{"group": "org.thisisthepy.compose.ui", "module": "ui-unpublished", "version": {"requires": v}}]
json.dump(m, open(path, "w"))
PY2
p="$repo/org/thisisthepy/compose/ui/ui/$V/ui-$V.pom"
python3 -I - "$p" "$V" <<'PY2'
import sys
path, v = sys.argv[1:]
text = open(path).read().replace("</project>",
    f"<dependencyManagement><dependencies><dependency><groupId>org.thisisthepy.compose.ui</groupId><artifactId>ui-unpublished</artifactId><version>{v}</version></dependency></dependencies></dependencyManagement></project>")
open(path, "w").write(text)
PY2
python3 -I "$tool" stage --from "$repo" --out "$out" > "$work/log"
python3 -I "$tool" sign "$out" > /dev/null
if python3 -I "$tool" validate "$out" > "$work/vlog" && grep -q "prune org.thisisthepy.compose.ui:ui:$V: constraint on ui-unpublished, variant on ui-iosarm64" "$work/log" &&
    ! grep -q "ui-iosarm64\|ui-unpublished" "$out/org/thisisthepy/compose/ui/ui/$V/ui-$V.module" "$out/org/thisisthepy/compose/ui/ui/$V/ui-$V.pom"; then
    pass "variants and constraints on what is not staged are taken out and listed"
else
    fail "pruning"; cat "$work/log" "$work/vlog" >&2
fi
grep -q "linuxX64ApiElements-published" "$out/org/thisisthepy/compose/ui/ui/$V/ui-$V.module" &&
    pass "a variant on a staged target is kept" || fail "a staged target's variant was pruned"

# A dev build: every version, and every reference to one, gets the suffix.
S=-dev.0123abc
repo="$work/dev-repo"; out="$work/dev-out"; rm -rf "$repo" "$out"
make_repo "$repo"
python3 -I "$tool" stage --from "$repo" --out "$out" --dev-suffix="$S" > "$work/log"
python3 -I "$tool" sign "$out" --checksums-only > /dev/null
if python3 -I "$tool" validate "$out" --dev="$S" --unsigned > "$work/log"; then
    pass "a dev build validates without signatures"
else
    fail "a dev build does not validate"; cat "$work/log" >&2
fi
d="$out/org/thisisthepy/compose/ui/ui/$V$S"
[[ -f "$d/ui-$V$S.module" && -f "$d/ui-$V$S.jar" && ! -e "$out/org/thisisthepy/compose/ui/ui/$V" ]] &&
    pass "dev directories and file names carry the suffix" || fail "dev renaming of directories and files"
python3 -I - "$d/ui-$V$S.module" "$V$S" <<'PY2' && pass "the dev module's component, available-at and files carry the suffix" || fail "dev module rewrite"
import json, sys
m = json.load(open(sys.argv[1])); v = sys.argv[2]
assert m["component"]["version"] == v
at = m["variants"][0]["available-at"]
assert at["version"] == v and at["url"] == f"../../ui-linuxx64/{v}/ui-linuxx64-{v}.module", at
assert m["variants"][1]["files"][0]["url"] == f"ui-{v}.jar"
PY2
grep -q "<version>$V$S</version>" "$out/org/thisisthepy/compose/ui/ui-linuxx64/$V$S/ui-linuxx64-$V$S.pom" &&
    ! grep -q "<version>$V</version>" "$out/org/thisisthepy/compose/ui/ui-linuxx64/$V$S/ui-linuxx64-$V$S.pom" &&
    pass "the dev POM's dependency on a fork artifact carries the suffix" || fail "dev POM rewrite"
expect_fail "a dev build is never a release" "local -dev build" "$out" --unsigned

# A reference left at the release version is caught even when that version is in the bundle.
p="$out/org/thisisthepy/compose/ui/ui-linuxx64/$V$S/ui-linuxx64-$V$S.pom"
sed -i.bak "s|<version>$V$S</version>|<version>$V</version>|g" "$p"; rm -f "$p.bak"
python3 -I - "$p" "$V" "$S" <<'PY2'
import re, sys
path, v, s = sys.argv[1:]
text = open(path).read()
# the POM's own version back to dev, the dependency left at the release
text = text.replace(f"<version>{v}</version>", f"<version>{v}{s}</version>", 1)
open(path, "w").write(text)
PY2
python3 -I "$tool" sign "$out" --checksums-only > /dev/null
expect_fail "a dev POM naming a release fork version fails" "fork version without $S" "$out" --dev="$S" --unsigned

# A two-commit suffix (a plugin built against a core dev build) is a dev build too.
repo2="$work/dev2-repo"; out="$work/dev2-out"; rm -rf "$repo2" "$out"
make_repo "$repo2"
S2=-dev.0123abcdef.fedcba9876
python3 -I "$tool" stage --from "$repo2" --out "$out" --dev-suffix="$S2" > /dev/null
python3 -I "$tool" sign "$out" --checksums-only > /dev/null
python3 -I "$tool" validate "$out" --dev="$S2" --unsigned > "$work/log" &&
    pass "a two-commit dev suffix validates" || { fail "a two-commit dev suffix"; cat "$work/log" >&2; }
if python3 -I "$tool" stage --from "$repo2" --out "$work/dev3-out" --dev-suffix=-dev.main > "$work/log" 2>&1; then
    fail "a dev suffix that is not a commit was accepted"
else
    pass "a dev suffix that is not a commit is refused"
fi

# put: files reach the server, each POM after its component's files, then maven-metadata.xml.
out="$work/dev-out2"; rm -rf "$out"
python3 -I "$tool" stage --from "$repo" --out "$out" --dev-suffix="$S" > /dev/null
python3 -I "$tool" sign "$out" --checksums-only > /dev/null
python3 -I - "$work/server.log" > "$work/port" <<'PY2' &
import http.server, sys, threading
log = open(sys.argv[1], "w")
class H(http.server.BaseHTTPRequestHandler):
    def do_PUT(self):
        auth = self.headers.get("Authorization", "")
        self.rfile.read(int(self.headers.get("Content-Length", 0)))
        log.write(f"PUT {self.path} {auth}\n"); log.flush()
        self.send_response(201 if auth.startswith("Basic ") else 401); self.end_headers()
    def do_GET(self):
        log.write(f"GET {self.path}\n"); log.flush()
        self.send_response(404); self.end_headers()
    def log_message(self, *a): pass
server = http.server.HTTPServer(("127.0.0.1", 0), H)
print(server.server_address[1], flush=True)
server.serve_forever()
PY2
server=$!
for _ in 1 2 3 4 5 6 7 8 9 10; do [[ -s "$work/port" ]] && break; sleep 0.3; done
if MAVEN_USERNAME=u MAVEN_PASSWORD=p python3 -I "$tool" put "$out" "http://127.0.0.1:$(cat "$work/port")/maven" > "$work/log" 2>&1; then
    pom_line="$(grep -n "PUT /maven/org/thisisthepy/compose/ui/ui/$V$S/ui-$V$S.pom " "$work/server.log" | cut -d: -f1)"
    jar_line="$(grep -n "PUT /maven/org/thisisthepy/compose/ui/ui/$V$S/ui-$V$S.jar " "$work/server.log" | cut -d: -f1)"
    meta_line="$(grep -n "PUT /maven/org/thisisthepy/compose/ui/ui/maven-metadata.xml " "$work/server.log" | cut -d: -f1)"
    if [[ -n "$pom_line" && -n "$jar_line" && -n "$meta_line" && "$jar_line" -lt "$pom_line" && "$pom_line" -lt "$meta_line" ]] &&
        ! grep -q "PUT .* Basic$" "$work/server.log"; then
        pass "put uploads files, then the POM, then maven-metadata.xml, with credentials"
    else
        fail "put order or credentials"; cat "$work/server.log" >&2
    fi
else
    fail "put failed"; cat "$work/log" >&2
fi
kill "$server" 2>/dev/null || true

if [[ $failures -gt 0 ]]; then
    echo "$failures failure(s)" >&2
    exit 1
fi
echo "all passed"
