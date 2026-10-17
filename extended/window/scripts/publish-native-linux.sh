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
anchor = "  - $compose.runtime\n"
if anchor not in text:
    sys.exit("native/linux/module.yaml has no $compose.runtime line to add the list after")
text = text.replace(anchor, anchor + mine.group(1), 1)
open(path, "w").write(text)
PY
    printf 'modules:\n  - common\n  - native/linux\n' > project.yaml
    echo "== native/linux as a one-target module: $arch"
    cat "$module"
    ./kotlin publish mavenLocal
done
