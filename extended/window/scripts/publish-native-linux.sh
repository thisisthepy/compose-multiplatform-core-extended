#!/usr/bin/env bash
# Publishes native/linux and native/linux-scene to the local Maven repository, one
# architecture at a time.
#
# Both list their Compose dependencies per target (the Linux Compose artifacts exist only as
# ui-linuxx64 and ui-linuxarm64; JetBrains' root coordinates have no Linux variant).
# Publishing a module with two targets also compiles the shared metadata source set, which
# sees only the dependencies of the common fragment, so none of the Compose types resolve and
# the publish fails. A module with one target has no such split: its dependencies are the
# target's own. So this publishes the modules twice, each time as one-target modules whose
# dependency lists are that target's, and puts the module files and project.yaml back
# afterwards.
#
# native/linux-scene depends on the scene, which is published before this runs (with its Linux
# targets, by the step before this one in extended-window-publish.yml). Here it is named by the
# scene's coordinate for the architecture being published rather than as a module of the
# project, because publishing the project would publish the scene again with one target only.
#
# Each run also writes the root coordinates (native-linux, native-linux-scene), listing only
# their own target, so the second run would leave each root naming one architecture. The two
# root .module files of each are merged afterwards so a consumer of the root resolves either
# architecture; the per-architecture coordinates (native-linux-linuxx64, ...) are distinct and
# are not touched.
#
# Run from extended/window. The Compose modules and the scene must already be in the local
# repository.
set -euo pipefail

cd "$(dirname "$0")/.."
modules=(native/linux native/linux-scene)
artifacts=(native-linux native-linux-scene)
for module in "${modules[@]}"; do cp "$module/module.yaml" "$module/module.yaml.orig"; done
cp project.yaml project.yaml.orig
restore() {
    for module in "${modules[@]}"; do mv "$module/module.yaml.orig" "$module/module.yaml"; done
    mv project.yaml.orig project.yaml
}
trap restore EXIT

repository="$HOME/.m2/repository/org/thisisthepy/compose/window"
roots="$(mktemp -d)"

for arch in linuxX64 linuxArm64; do
    for module in "${modules[@]}"; do
        cp "$module/module.yaml.orig" "$module/module.yaml"
        python3 - "$module/module.yaml" "$arch" <<'PY'
import re
import sys

path, arch = sys.argv[1], sys.argv[2]
text = open(path).read()
both = "platforms: [ linuxX64, linuxArm64 ]"
if both not in text:
    sys.exit("%s no longer lists both platforms on one line" % path)
text = text.replace(both, "platforms: [ %s ]" % arch)
mine = re.search(r"^dependencies@%s:\n((?:  - .*\n)+)" % arch, text, re.M)
if mine is None:
    sys.exit("%s has no dependencies@%s list" % (path, arch))
text = re.sub(r"^dependencies@linux(?:X64|Arm64):\n(?:  - .*\n)+\n?", "", text, flags=re.M)
anchor = "  - ../../common\n"
if anchor not in text:
    sys.exit("%s has no ../../common line to add the list after" % path)
text = text.replace(anchor, anchor + mine.group(1), 1)
# The scene by its published coordinate for this architecture, not as a module of the project.
text = text.replace("  - ../../scene\n", "  - org.thisisthepy.compose.window:scene-%s:1.11.1-ext.1\n" % arch.lower())
open(path, "w").write(text)
PY
        echo "== $module as a one-target module: $arch"
        cat "$module/module.yaml"
    done
    printf 'modules:\n  - common\n  - native/linux\n  - native/linux-scene\n' > project.yaml
    ./kotlin publish mavenLocal
    for artifact in "${artifacts[@]}"; do
        root="$repository/$artifact/1.11.1-ext.1/$artifact-1.11.1-ext.1.module"
        [[ -f "$root" ]] || { echo "the publish left no root module at $root" >&2; exit 1; }
        cp "$root" "$roots/$artifact-$arch.module"
    done
done

for artifact in "${artifacts[@]}"; do
    root="$repository/$artifact/1.11.1-ext.1/$artifact-1.11.1-ext.1.module"
    python3 - "$roots/$artifact-linuxX64.module" "$roots/$artifact-linuxArm64.module" "$root" <<'PY'
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
    echo "== the root module of $artifact after merging:"
    cat "$root"
done
