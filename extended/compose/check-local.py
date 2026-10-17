#!/usr/bin/env python3
"""Checks what publish-local.sh left in the local Maven repository.

Usage: python3 extended/compose/check-local.py [<maven repository>]

The repository defaults to ~/.m2/repository. Three things are checked, and each failure is
printed with the file that shows it:

1. Every dependency a published module names under org.thisisthepy.compose is in the
   repository at the version it names. One that is not sends the consumer's build to the
   network for a coordinate nobody publishes.
2. No published module names a JetBrains Compose module that carries classes (anything under
   org.jetbrains.compose other than the runtime and the internal annotation and collection
   libraries, which are stubs pointing at androidx, as this fork's are). Such a dependency puts
   JetBrains' copy of the same classes next to this fork's, which no resolver can merge because
   the groups differ.
3. Nothing under org.jetbrains.compose or org.jetbrains.androidx carries a version of this
   fork (-ext.<N>). An artifact at JetBrains' coordinates that JetBrains did not build is one
   a consumer cannot tell from the real thing.

Exits 1 if anything failed, 0 otherwise.
"""

import json
import os
import re
import sys

FORK = "org.thisisthepy.compose"
# The window modules are this fork's too, but versioned on their own and built by Amper.
OWN_VERSIONED = ("org.thisisthepy.compose.window",)
# JetBrains groups this fork's modules may depend on: compatibility stubs with no classes,
# which point at the same androidx artifacts as this fork's own stubs do.
TAKEN_FROM_JETBRAINS = {
    "org.jetbrains.compose.runtime",
    "org.jetbrains.compose.annotation-internal",
    "org.jetbrains.compose.collection-internal",
}
FORK_VERSION = re.compile(r"-ext\.\d+(-dev)?$")


def coordinates_of(repository, path):
    """group, artifact, version of a file at <group path>/<artifact>/<version>/<file>."""
    relative = os.path.relpath(path, repository).split(os.sep)
    return ".".join(relative[:-3]), relative[-3], relative[-2]


def has(repository, group, artifact, version):
    base = os.path.join(repository, *group.split("."), artifact, version, f"{artifact}-{version}")
    return os.path.isfile(base + ".pom") or os.path.isfile(base + ".module")


def dependencies_of(path):
    """(group, module, version) for every dependency and constraint a .module or .pom names."""
    found = []
    if path.endswith(".module"):
        with open(path, encoding="utf-8") as handle:
            metadata = json.load(handle)
        for variant in metadata.get("variants", []):
            for key in ("dependencies", "dependencyConstraints"):
                for dependency in variant.get(key, []):
                    version = dependency.get("version", {})
                    found.append((
                        dependency["group"],
                        dependency["module"],
                        version.get("requires") or version.get("strictly") or version.get("prefers"),
                        key == "dependencies",
                    ))
            # A root's variants point at every target's own module with available-at; only the
            # targets that were built are here, and a consumer reads only its own, so those are
            # not dependencies to check.
    else:
        with open(path, encoding="utf-8") as handle:
            text = handle.read()
        for body in re.findall(r"<dependency>(.*?)</dependency>", text, re.S):
            group = re.search(r"<groupId>\s*([^<\s]+)", body)
            artifact = re.search(r"<artifactId>\s*([^<\s]+)", body)
            version = re.search(r"<version>\s*([^<\s]+)", body)
            if group and artifact:
                found.append((group.group(1), artifact.group(1), version.group(1) if version else None, True))
    return found


def main():
    repository = sys.argv[1] if len(sys.argv) > 1 else os.path.expanduser("~/.m2/repository")
    problems = []
    published = 0

    fork_root = os.path.join(repository, *FORK.split("."))
    for directory, _, files in os.walk(fork_root):
        for name in files:
            if not (name.endswith(".module") or name.endswith(".pom")):
                continue
            path = os.path.join(directory, name)
            group, artifact, version = coordinates_of(repository, path)
            if group.startswith(OWN_VERSIONED):
                continue
            published += 1
            for dep_group, dep_module, dep_version, required in dependencies_of(path):
                if dep_group.startswith(FORK) and not dep_group.startswith(OWN_VERSIONED):
                    if required and dep_version and not has(repository, dep_group, dep_module, dep_version):
                        problems.append(f"{dep_group}:{dep_module}:{dep_version} is not published, named by {path}")
                elif required and dep_group.startswith("org.jetbrains.compose") and dep_group not in TAKEN_FROM_JETBRAINS:
                    problems.append(
                        f"{dep_group}:{dep_module}:{dep_version} is JetBrains' copy of a module this fork "
                        f"publishes, named by {path}"
                    )

    for jetbrains in ("org.jetbrains.compose", "org.jetbrains.androidx"):
        root = os.path.join(repository, *jetbrains.split("."))
        for directory, subdirectories, _ in os.walk(root):
            for version in subdirectories:
                if FORK_VERSION.search(version):
                    problems.append(f"{os.path.join(directory, version)} is a fork version at JetBrains' coordinates")

    if published == 0:
        problems.append(f"nothing is published under {fork_root}")
    for problem in sorted(set(problems)):
        print(problem)
    if problems:
        return 1
    print(f"ok: {published} published metadata file(s) under {fork_root}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
