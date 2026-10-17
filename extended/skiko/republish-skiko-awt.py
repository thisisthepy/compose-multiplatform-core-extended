#!/usr/bin/env python3
"""Writes skiko's skiko-awt publication under this fork's coordinate.

Usage: republish-skiko-awt.py <from-dir> <to-dir> <upstream-version> <group> <version> <capability-version>

<from-dir> holds what skiko's build published as org.jetbrains.skiko:skiko-awt:<upstream-version>.
<to-dir> gets the same files as <group>:skiko-awt:<version>:

- the jar and the sources jar, renamed and otherwise as they are;
- the .pom with its own group and version, the upstream version in its description, and the
  dependencies (the natives, skiko-awt-runtime-*) as they were;
- the .module with its own component (no longer pointing at JetBrains' skiko root), the files
  renamed, and every variant declaring two
  capabilities: its own coordinate, and JetBrains' org.jetbrains.skiko:skiko-awt at
  <capability-version>, so Gradle treats the two as one library and a build that resolves the
  conflict by version keeps this one.

Checksum sidecars are left out: they are of the files before renaming.
"""

import json
import os
import re
import shutil
import sys

ARTIFACT = "skiko-awt"
UPSTREAM_GROUP = "org.jetbrains.skiko"


def main(argv):
    source, target, upstream, group, version, capability = argv
    old_prefix = f"{ARTIFACT}-{upstream}"
    new_prefix = f"{ARTIFACT}-{version}"
    os.makedirs(target, exist_ok=True)
    for name in sorted(os.listdir(source)):
        if name.endswith((".md5", ".sha1", ".sha256", ".sha512")):
            continue
        renamed = name.replace(old_prefix, new_prefix, 1)
        path = os.path.join(source, name)
        out = os.path.join(target, renamed)
        if name.endswith(".pom"):
            text = open(path, encoding="utf-8").read()
            # The project's own coordinate is the first groupId and version in the file; the
            # dependencies after it keep theirs.
            text = text.replace(f"<groupId>{UPSTREAM_GROUP}</groupId>", f"<groupId>{group}</groupId>", 1)
            text = text.replace(f"<version>{upstream}</version>", f"<version>{version}</version>", 1)
            note = f"skiko {upstream} (JetBrains/skiko) with the AWT switch. Upstream version: {upstream}."
            if re.search(r"<description>.*?</description>", text, re.S):
                text = re.sub(r"<description>(.*?)</description>",
                              lambda m: f"<description>{m.group(1).strip()} {note}</description>", text, count=1, flags=re.S)
            else:
                text = text.replace("</artifactId>", f"</artifactId>\n  <description>{note}</description>", 1)
            open(out, "w", encoding="utf-8").write(text)
        elif name.endswith(".module"):
            metadata = json.load(open(path, encoding="utf-8"))
            metadata["component"].update({"group": group, "module": ARTIFACT, "version": version})
            # skiko-awt's own metadata says it is a variant of JetBrains' skiko root ("url" to
            # skiko/<version>/skiko-<version>.module), which would send a consumer back to JetBrains'
            # coordinates. Published on its own, it is its own component.
            metadata["component"].pop("url", None)
            for variant in metadata.get("variants", []):
                for entry in variant.get("files", []):
                    for key in ("name", "url"):
                        if key in entry:
                            entry[key] = entry[key].replace(old_prefix, new_prefix)
                variant["capabilities"] = [
                    {"group": group, "name": ARTIFACT, "version": version},
                    {"group": UPSTREAM_GROUP, "name": ARTIFACT, "version": capability},
                ]
            json.dump(metadata, open(out, "w", encoding="utf-8"), indent=2)
        else:
            shutil.copyfile(path, out)
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 7:
        print(__doc__.strip().splitlines()[2], file=sys.stderr)
        sys.exit(2)
    sys.exit(main(sys.argv[1:]))
