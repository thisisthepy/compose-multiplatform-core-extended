#!/usr/bin/env python3
"""Points this fork's desktop modules at this fork's skiko-awt instead of JetBrains' skiko.

Usage: repoint-desktop-skiko.py <repository> [--require]

<repository> is a Maven repository layout (~/.m2/repository, or a staged bundle). Every module
under org/thisisthepy/compose/**/<name>-desktop/<version> that depends on
org.jetbrains.skiko:skiko is changed, in its .module and its .pom, to depend on
org.thisisthepy.compose.skiko:skiko-awt:<version> (the fork publishes both on one version line).

Why. Compose declares skiko once, in a source set shared by every target, so the desktop
modules inherit a dependency on JetBrains' skiko root, which resolves to JetBrains' skiko-awt.
Next to this fork's skiko-awt that puts two copies of skiko's classes on a class path. The
fork's skiko-awt no longer claims JetBrains' coordinate as a capability (Amper rejects a
variant with one), so nothing else makes them one library. Kotlin/Native and web modules are
not touched: they use skiko's klib and wasm artifacts, not skiko-awt.

--require fails when no desktop module was found, for a caller that has published them.
"""

import json
import os
import re
import sys

UPSTREAM_GROUP = "org.jetbrains.skiko"
GROUP = "org.thisisthepy.compose.skiko"
ARTIFACT = "skiko-awt"


def repoint(repository):
    base = os.path.join(repository, "org", "thisisthepy", "compose")
    changed = []
    for directory, _, files in os.walk(base):
        version = os.path.basename(directory)
        artifact = os.path.basename(os.path.dirname(directory))
        if not artifact.endswith("-desktop"):
            continue
        for name in files:
            path = os.path.join(directory, name)
            if name.endswith(".module"):
                metadata = json.load(open(path, encoding="utf-8"))
                touched = False
                for variant in metadata.get("variants", []):
                    for dependency in variant.get("dependencies", []):
                        if dependency.get("group") == UPSTREAM_GROUP and dependency.get("module") == "skiko":
                            dependency.update({"group": GROUP, "module": ARTIFACT, "version": {"requires": version}})
                            touched = True
                if touched:
                    json.dump(metadata, open(path, "w", encoding="utf-8"), indent=2)
                    changed.append(path)
            elif name.endswith(".pom"):
                text = open(path, encoding="utf-8").read()
                pattern = re.compile(
                    r"<groupId>" + re.escape(UPSTREAM_GROUP) + r"</groupId>(\s*)<artifactId>skiko</artifactId>(\s*)<version>[^<]*</version>")
                new = pattern.sub(lambda m: f"<groupId>{GROUP}</groupId>{m.group(1)}<artifactId>{ARTIFACT}</artifactId>"
                                            f"{m.group(2)}<version>{version}</version>", text)
                if new != text:
                    open(path, "w", encoding="utf-8").write(new)
                    changed.append(path)
    return changed


def main(argv):
    require = "--require" in argv
    args = [a for a in argv if a != "--require"]
    if len(args) != 1:
        print(__doc__.strip().splitlines()[2], file=sys.stderr)
        return 2
    changed = repoint(args[0])
    if not changed and require:
        sys.exit(f"error: no desktop module under {args[0]}/org/thisisthepy/compose depends on {UPSTREAM_GROUP}:skiko")
    print(f"{len(changed)} desktop metadata files now name {GROUP}:{ARTIFACT}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
