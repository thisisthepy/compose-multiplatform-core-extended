#!/usr/bin/env python3
"""Writes skiko's skiko-awt publication under this fork's coordinate.

Usage: republish-skiko-awt.py <from-dir> <to-dir> <upstream-version> <group> <version>

<from-dir> holds what skiko's build published as org.jetbrains.skiko:skiko-awt:<upstream-version>.
<to-dir> gets the same files as <group>:skiko-awt:<version>:

- the jar and the sources jar, renamed and otherwise as they are;
- the .pom with its own group and version, the upstream version in its description, and the
  dependencies (the natives, skiko-awt-runtime-*) as they were, each excluding JetBrains'
  skiko-awt, which the natives depend on;
- the .module with its own component (no longer pointing at JetBrains' skiko root), the files
  renamed, and every variant declaring one capability: its own coordinate. Amper accepts a
  variant only when its capabilities are empty or exactly the module's own coordinate, so a
  second (JetBrains') capability left it with no usable variant. What keeps JetBrains' skiko-awt
  off a class path is repoint-desktop-skiko.py, which points this fork's desktop modules here.

Checksum sidecars are left out: they are of the files before renaming.
"""

import json
import os
import re
import shutil
import sys

ARTIFACT = "skiko-awt"
UPSTREAM_GROUP = "org.jetbrains.skiko"


# JetBrains' natives (org.jetbrains.skiko:skiko-awt-runtime-<target>) each depend on
# org.jetbrains.skiko:skiko-awt. Next to this fork's skiko-awt that is skiko's classes twice on a
# class path: ProGuard and uber jars stop on the duplicates, and a JVM loads whichever comes first
# (#317). Every dependency on the natives excludes it; Gradle drops a module only when every path
# to it does.
NATIVES_PREFIX = "skiko-awt-runtime"
NATIVES_EXCLUSION = {"group": "org.jetbrains.skiko", "module": "skiko-awt"}


def exclude_jetbrains_skiko_awt_from_natives(metadata):
    """Adds the exclusion to a .module's dependencies on the natives; True if it changed any."""
    touched = False
    for variant in metadata.get("variants", []):
        for dependency in variant.get("dependencies", []):
            if dependency.get("group") == "org.jetbrains.skiko" and dependency.get("module", "").startswith(NATIVES_PREFIX):
                excludes = dependency.setdefault("excludes", [])
                if NATIVES_EXCLUSION not in excludes:
                    excludes.append(dict(NATIVES_EXCLUSION))
                    touched = True
    return touched


def exclude_jetbrains_skiko_awt_from_natives_pom(text):
    """The same for a .pom's <dependency> blocks on the natives that have no <exclusions> yet."""
    def edit(match):
        block = match.group(0)
        if "<exclusions>" in block:
            return block
        return re.sub(r"(<version>[^<]*</version>)",
                      r"\1<exclusions><exclusion><groupId>org.jetbrains.skiko</groupId>"
                      r"<artifactId>skiko-awt</artifactId></exclusion></exclusions>", block, count=1)
    return re.sub(r"<dependency>(?:(?!</dependency>).)*?<groupId>org\.jetbrains\.skiko</groupId>\s*"
                  r"<artifactId>" + NATIVES_PREFIX + r"[^<]*</artifactId>(?:(?!</dependency>).)*?</dependency>",
                  edit, text, flags=re.S)


def main(argv):
    source, target, upstream, group, version = argv
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
            text = exclude_jetbrains_skiko_awt_from_natives_pom(text)
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
                variant["capabilities"] = [{"group": group, "name": ARTIFACT, "version": version}]
            exclude_jetbrains_skiko_awt_from_natives(metadata)
            json.dump(metadata, open(out, "w", encoding="utf-8"), indent=2)
        else:
            shutil.copyfile(path, out)
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 6:
        print(__doc__.strip().splitlines()[2], file=sys.stderr)
        sys.exit(2)
    sys.exit(main(sys.argv[1:]))
