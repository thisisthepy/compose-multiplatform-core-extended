#!/usr/bin/env python3
"""Writes skiko for mingwX64 under this fork's coordinate, with its C++ half beside it, and
points this fork's mingwX64 modules at it.

Usage: republish-skiko-mingw.py <repository> <upstream-version> <version> <cpp-dir>

<repository> is the local Maven repository (~/.m2/repository). build-skiko-mingw.sh published
the Kotlin half there as org.jetbrains.skiko:skiko-mingwx64:<upstream-version> and wrote the C++
half to <cpp-dir> (skiko-bridges.lib, embedded_icu.obj, skia/*.lib). JetBrains publishes no
skiko for mingwX64, so nothing under org.jetbrains can ship it; this writes:

  org/thisisthepy/compose/skiko/skiko-mingwx64/<version>/
    skiko-mingwx64-<version>.klib, .module, .pom    the Kotlin half, its own component
    skiko-mingwx64-<version>-msvc.zip               the C++ half, for the link

The klib carries no native code. JetBrains' skiko klibs embed their C++ half and Skia with
-include-binary, so Kotlin/Native's own linker takes them from the klib. Here that linker is
MinGW's and the C++ half is MSVC's (it has to match JetBrains' prebuilt Windows Skia), so the
executable is linked by MSVC instead, by the org.thisisthepy.kotlin.native.desktop plugin,
which finds this module among a program's dependencies and links the zip's contents:

  obj/embedded_icu.obj         Skia's ICU data compiled in, linked whole: the program is one
                               file, with no icudtl.dat beside it
  lib/*.lib                    skiko's bridges and Skia, static C runtime (/MT)
  msvc-link.properties         systemLibraries (Windows import libraries Skia calls into) and
                               cppStandardLibrary=true (Skia is C++ built with MSVC)

Then every module under org/thisisthepy/compose/**/<name>-mingwx64/<version> that depends on
org.jetbrains.skiko:skiko is pointed at org.thisisthepy.compose.skiko:skiko-mingwx64:<version>
instead, in its .module and its .pom: JetBrains' skiko root, which a consumer resolves from
Maven Central, knows no mingw_x64 variant.

Checksum sidecars are left out: they are of the files before renaming.
"""

import json
import os
import re
import shutil
import sys
import zipfile

UPSTREAM_GROUP = "org.jetbrains.skiko"
GROUP = "org.thisisthepy.compose.skiko"
ARTIFACT = "skiko-mingwx64"

# What Skia and skiko's bridges call in Windows, as skiko's own Windows link names it
# (skiko/buildSrc/.../JvmTasksConfiguration.kt), and what the JVM half there loads at run time
# but a static link has to name: OpenGL, Direct3D 12 and DXGI, the D3D compiler, DirectWrite.
SYSTEM_LIBRARIES = [
    "advapi32.lib", "gdi32.lib", "dwmapi.lib", "ole32.lib", "propsys.lib", "shcore.lib",
    "shlwapi.lib", "user32.lib", "uuid.lib", "opengl32.lib", "d3d12.lib", "dxgi.lib",
    "d3dcompiler.lib", "dwrite.lib", "windowscodecs.lib", "usp10.lib",
]


def republish(repository, upstream, version):
    source = os.path.join(repository, "org", "jetbrains", "skiko", ARTIFACT, upstream)
    target = os.path.join(repository, *GROUP.split("."), ARTIFACT, version)
    if not os.path.isdir(source):
        sys.exit(f"error: no {UPSTREAM_GROUP}:{ARTIFACT}:{upstream} in {repository}; run build-skiko-mingw.sh first")
    old_prefix = f"{ARTIFACT}-{upstream}"
    new_prefix = f"{ARTIFACT}-{version}"
    os.makedirs(target, exist_ok=True)
    for name in sorted(os.listdir(source)):
        if name.endswith((".md5", ".sha1", ".sha256", ".sha512")):
            continue
        path = os.path.join(source, name)
        out = os.path.join(target, name.replace(old_prefix, new_prefix, 1))
        if name.endswith(".pom"):
            text = open(path, encoding="utf-8").read()
            text = text.replace(f"<groupId>{UPSTREAM_GROUP}</groupId>", f"<groupId>{GROUP}</groupId>", 1)
            text = text.replace(f"<version>{upstream}</version>", f"<version>{version}</version>", 1)
            note = (f"skiko {upstream} (JetBrains/skiko) for Kotlin/Native on Windows; the C++ half is "
                    f"the msvc classifier. Upstream version: {upstream}.")
            if re.search(r"<description>.*?</description>", text, re.S):
                text = re.sub(r"<description>(.*?)</description>",
                              lambda m: f"<description>{m.group(1).strip()} {note}</description>", text, count=1, flags=re.S)
            else:
                text = text.replace("</artifactId>", f"</artifactId>\n  <description>{note}</description>", 1)
            open(out, "w", encoding="utf-8").write(text)
        elif name.endswith(".module"):
            metadata = json.load(open(path, encoding="utf-8"))
            metadata["component"].update({"group": GROUP, "module": ARTIFACT, "version": version})
            # Published by skiko's build as a variant of JetBrains' skiko root ("url" back to it);
            # on its own coordinate it is its own component.
            metadata["component"].pop("url", None)
            for variant in metadata.get("variants", []):
                for entry in variant.get("files", []):
                    for key in ("name", "url"):
                        if key in entry:
                            entry[key] = entry[key].replace(old_prefix, new_prefix)
            json.dump(metadata, open(out, "w", encoding="utf-8"), indent=2)
        else:
            shutil.copyfile(path, out)
    return target


def package_cpp(target, version, cpp):
    bridges = os.path.join(cpp, "skiko-bridges.lib")
    icu = os.path.join(cpp, "embedded_icu.obj")
    skia = os.path.join(cpp, "skia")
    if not os.path.isfile(bridges) or not os.path.isfile(icu) or not os.path.isdir(skia):
        sys.exit(f"error: {cpp} holds no skiko-bridges.lib, embedded_icu.obj and skia/; "
                 "run build-skiko-mingw.sh without --kotlin-only")
    archive = os.path.join(target, f"{ARTIFACT}-{version}-msvc.zip")
    libraries = 0
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as zip:
        zip.write(icu, "obj/embedded_icu.obj")
        zip.write(bridges, "lib/skiko-bridges.lib")
        # Libraries only: nothing goes beside the executable, icudtl.dat least of all.
        for name in sorted(os.listdir(skia)):
            if name.endswith(".lib"):
                zip.write(os.path.join(skia, name), f"lib/{name}")
                libraries += 1
        zip.writestr("msvc-link.properties",
                     "systemLibraries=" + ",".join(SYSTEM_LIBRARIES) + "\ncppStandardLibrary=true\n")
    if libraries == 0:
        sys.exit(f"error: no Skia libraries in {skia}")
    return archive


def repoint(repository, version):
    """Points this fork's mingwX64 modules at the republished skiko."""
    base = os.path.join(repository, "org", "thisisthepy", "compose")
    changed = []
    for directory, _, files in os.walk(base):
        if os.path.basename(directory) != version:
            continue
        artifact = os.path.basename(os.path.dirname(directory))
        if not artifact.endswith("-mingwx64") or artifact == ARTIFACT:
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
    repository, upstream, version, cpp = argv
    target = republish(repository, upstream, version)
    archive = package_cpp(target, version, cpp)
    changed = repoint(repository, version)
    if not changed:
        sys.exit(f"error: no mingwX64 module under {repository}/org/thisisthepy/compose depends on {UPSTREAM_GROUP}:skiko; "
                 "publish Compose for mingwX64 first")
    print(f"{GROUP}:{ARTIFACT}:{version} with {os.path.basename(archive)}; "
          f"{len(changed)} mingwX64 metadata files now name it")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 5:
        print(__doc__.strip().splitlines()[2], file=sys.stderr)
        sys.exit(2)
    sys.exit(main(sys.argv[1:]))
