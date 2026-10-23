#!/usr/bin/env bash
# Publishes native/linux to the local Maven repository, one architecture at a time.
#
# native/linux lists its Compose dependencies per target (the Linux Compose artifacts exist
# only as ui-linuxx64 and ui-linuxarm64; JetBrains' root coordinates have no Linux variant).
# Publishing a module with two targets also compiles the shared metadata source set, which
# sees only the dependencies of the common fragment, so none of the Compose types resolve and
# the publish fails. A module with one target has no such split: its dependencies are the
# target's own. So this publishes the module twice, each time as a one-target module whose
# dependency list is that target's, and puts module.yaml and project.yaml back afterwards.
#
# Each run also writes the root coordinate (native-linux), listing only its own target, so the
# second run would leave the root naming one architecture. The two root .module files are merged
# afterwards so a consumer of the root resolves either architecture; the per-architecture
# coordinates (native-linux-linuxx64, native-linux-linuxarm64) are distinct and are not touched.
#
# Run from extended/window. The Compose modules must already be in the local repository.
set -euo pipefail

cd "$(dirname "$0")/.."
module=native/linux/module.yaml
cp "$module" "$module.orig"
cp project.yaml project.yaml.orig
restore() {
    mv "$module.orig" "$module"
    mv project.yaml.orig project.yaml
}
trap restore EXIT

root="$HOME/.m2/repository/org/thisisthepy/compose/window/native-linux/1.11.1-ext.1/native-linux-1.11.1-ext.1.module"
roots="$(mktemp -d)"

for arch in linuxX64 linuxArm64; do
    cp "$module.orig" "$module"
    python3 - "$module" "$arch" <<'PY'
import re
import sys

path, arch = sys.argv[1], sys.argv[2]
text = open(path).read()
both = "platforms: [ linuxX64, linuxArm64 ]"
if both not in text:
    sys.exit("native/linux/module.yaml no longer lists both platforms on one line")
text = text.replace(both, "platforms: [ %s ]" % arch)
mine = re.search(r"^dependencies@%s:\n((?:  - .*\n)+)" % arch, text, re.M)
if mine is None:
    sys.exit("native/linux/module.yaml has no dependencies@%s list" % arch)
text = re.sub(r"^dependencies@linux(?:X64|Arm64):\n(?:  - .*\n)+\n?", "", text, flags=re.M)
anchor = "  - ../../common\n"
if anchor not in text:
    sys.exit("native/linux/module.yaml has no ../../common line to add the list after")
text = text.replace(anchor, anchor + mine.group(1), 1)
open(path, "w").write(text)
PY
    printf 'modules:\n  - common\n  - native/linux\n' > project.yaml
    echo "== native/linux as a one-target module: $arch"
    cat "$module"
    ./kotlin publish mavenLocal
    [[ -f "$root" ]] || { echo "the publish left no root module at $root" >&2; exit 1; }
    cp "$root" "$roots/$arch.module"
done

python3 - "$roots/linuxX64.module" "$roots/linuxArm64.module" "$root" <<'PY'
import json
import sys

first, second, out = sys.argv[1:4]
merged = json.load(open(first))
other = json.load(open(second))
seen = {json.dumps(v, sort_keys=True) for v in merged["variants"]}
for variant in other["variants"]:
    key = json.dumps(variant, sort_keys=True)
    if key not in seen:
        merged["variants"].append(variant)
        seen.add(key)
json.dump(merged, open(out, "w"), indent=2)
PY
echo "== the root module after merging:"
cat "$root"
