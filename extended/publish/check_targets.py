#!/usr/bin/env python3
"""Fails when a bundle misses a coordinate or a target that upstream Compose Multiplatform has.

Usage:
  check_targets.py <bundle dir> --catalog <JetBrainsPublication.kt> --platforms <ComposePlatforms.kt>
                   [--coordinates <COORDINATES.md>] [--skip-target <target>...]

What a bundle must hold is read from the sources the build publishes from, not from a list kept
here, so a library or a target added to the catalogue is asked for the next time the bundle is
checked:

- ComposePlatforms.kt names the platforms and the groups of them (ALL, SKIKO_SUPPORT, ...).
- JetBrainsPublication.kt names every library, its modules (ComposeComponent) and the platforms
  each module publishes for (supportedPlatforms, SKIKO_SUPPORT when it names none).
- COORDINATES.md lists every artifact of this fork, one row each, in its last column.

For every module the bundle needs the root (<group>:<artifact>), one component per platform the
module supports (<artifact>-<target>), and, in the root's Gradle module metadata, a variant for
that platform, because a consumer on a target the root does not list is told the module does not
support it. Android is not published by this fork: the root points it at androidx, so the root
must list an android variant and no component is asked for. A module with custom tasks (the
desktop module and its per-system jars) is checked for its root only. Versions are not compared:
any version of a component counts, so the same check serves a release and a dev build. A
module JetBrainsPublication.kt lists as having an Android target of its own (ui-backhandler) is
not asked for an android variant either.

Exits 1 on any gap, printing each one.
"""

import argparse
import glob
import json
import os
import re
import sys

FORK_GROUP = "org.thisisthepy.compose."
# Kotlin names the artifact of a target after it, lower case, but for these.
TARGET_ARTIFACT_ALIASES = {
    "desktop": ("desktop", "jvm"),
    "wasmjs": ("wasm-js", "wasmjs"),
}
SPECIAL_JVM_COMPONENTS = {":annotation:annotation", ":collection:collection", ":lifecycle:lifecycle-common"}


def strip_comments(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def matching(text, start):
    """The index of the parenthesis closing the one at text[start]."""
    depth = 0
    for index in range(start, len(text)):
        if text[index] == "(":
            depth += 1
        elif text[index] == ")":
            depth -= 1
            if depth == 0:
                return index
    raise ValueError("unbalanced parentheses")


def split_top_level(text, separator=","):
    parts, depth, current = [], 0, []
    for char in text:
        if char in "([":
            depth += 1
        elif char in ")]":
            depth -= 1
        if char == separator and depth == 0:
            parts.append("".join(current))
            current = []
        else:
            current.append(char)
    parts.append("".join(current))
    return [part.strip() for part in parts if part.strip()]


def evaluate(expression, names):
    """A set expression of platform names: NAME, EnumSet.of(A, B), setOf(A), + and -."""
    expression = expression.strip()
    expression = re.sub(r"\bComposePlatforms\.", "", expression)
    expression = re.sub(r"EnumSet\.allOf\([^)]*\)", "ALL", expression)
    expression = re.sub(r"(?:EnumSet\.of|setOf)\(([^)]*)\)", lambda m: "(" + " + ".join(
        part.strip() for part in m.group(1).split(",") if part.strip()) + ")", expression)
    tokens = re.findall(r"[A-Za-z_][A-Za-z0-9_]*|[+\-()]", expression)
    position = 0

    def term():
        nonlocal position
        token = tokens[position]
        position += 1
        if token == "(":
            value = chain()
            position += 1  # ")"
            return value
        if token not in names:
            raise ValueError(f"unknown platform or group '{token}' in '{expression}'")
        return set(names[token])

    def chain():
        nonlocal position
        value = term()
        while position < len(tokens) and tokens[position] in "+-":
            operator = tokens[position]
            position += 1
            other = term()
            value = value | other if operator == "+" else value - other
        return value

    return chain()


def read_platforms(path):
    """{name: set of enum entries} for the enum's entries and the groups in its companion."""
    with open(path, encoding="utf-8") as handle:
        text = strip_comments(handle.read())
    body = text[text.index("enum class ComposePlatforms"):]
    entries = re.findall(r"^\s{4}([A-Z][A-Za-z0-9]*)\(", body, re.M)
    names = {entry: {entry} for entry in entries}
    names["ALL"] = set(entries)
    for name, expression in re.findall(
            r"val\s+([A-Z_]+)\s*=\s*((?:[^\n]|\n(?!\s*(?:val|fun|/\*\*|\}))\s*)+)", body):
        if name == "ALL":
            continue
        names[name] = evaluate(expression.replace("\n", " "), names)
    return names


def read_android_targets(path):
    """The projects JetBrainsPublication.kt says have an Android target of their own, which the
    root therefore does not redirect to androidx."""
    with open(path, encoding="utf-8") as handle:
        text = strip_comments(handle.read())
    match = re.search(r"jetBrainsProjectsWithAndroidTarget\s*=\s*setOf\(([^)]*)\)", text)
    return set(re.findall(r'"(:[^"]+)"', match.group(1))) if match else set()


def read_catalog(path, names):
    """[(library, project path, supported platforms, has custom tasks)] in file order."""
    with open(path, encoding="utf-8") as handle:
        text = strip_comments(handle.read())
    start = text.index("val libraryToComponents")
    start = text.index("mapOf(", start) + len("mapOf")
    body = text[start:matching(text, start)]
    components = []
    for match in re.finditer(r'"([A-Z0-9_]+)"\s+to\s+listOf\(', body):
        listing = body[match.end() - 1:matching(body, match.end() - 1) + 1]
        for component in re.finditer(r"ComposeComponent\(", listing):
            arguments = listing[component.end() - 1 + 1:matching(listing, component.end() - 1)]
            positional, named = [], {}
            for argument in split_top_level(arguments):
                key = re.match(r"([A-Za-z]+)\s*=(?!=)\s*(.*)", argument, re.S)
                if key:
                    named[key.group(1)] = key.group(2)
                else:
                    positional.append(argument)
            path_literal = named.get("path") or positional[0]
            project = re.search(r'"(:[^"]+)"', path_literal).group(1)
            supported = named.get("supportedPlatforms") or (positional[1] if len(positional) > 1 else None)
            platforms = evaluate(supported, names) if supported else set(names["SKIKO_SUPPORT"])
            components.append((match.group(1), project, platforms, "customTasks" in named))
    if not components:
        raise ValueError(f"{path}: no ComposeComponent found")
    return components


def fork_coordinate(project):
    """group, artifact of a project path, as JetBrainsPublication.mavenGroupFor and the name say."""
    artifact = project.rsplit(":", 1)[1]
    if project == ":annotation:annotation":
        return FORK_GROUP + "annotation-internal", artifact
    if project == ":collection:collection":
        return FORK_GROUP + "collection-internal", artifact
    parent = project.rsplit(":", 1)[0]
    if project.startswith(":compose:"):
        return FORK_GROUP + parent[len(":compose:"):].replace(":", "."), artifact
    return FORK_GROUP + "androidx." + parent[1:].replace(":", "."), artifact


def bundle_index(bundle):
    """{(group, artifact): [directory of each version]} for every POM in the bundle."""
    index = {}
    for pom in glob.glob(os.path.join(bundle, "**", "*.pom"), recursive=True):
        directory = os.path.dirname(pom)
        relative = os.path.relpath(directory, bundle).split(os.sep)
        if len(relative) < 3:
            continue
        group, artifact, version = ".".join(relative[:-2]), relative[-2], relative[-1]
        if os.path.basename(pom) == f"{artifact}-{version}.pom":
            index.setdefault((group, artifact), []).append(directory)
    return index


def root_variants(directories):
    names = set()
    for directory in directories:
        for module in glob.glob(os.path.join(directory, "*.module")):
            try:
                with open(module, encoding="utf-8") as handle:
                    names.update(v.get("name", "") for v in json.load(handle).get("variants", []))
            except ValueError:
                pass
    return names


def lists_target(variants, target):
    """Whether a root variant name starts with the target (iosArm64ApiElements-published)."""
    wanted = {alias.replace("-", "").lower() for alias in TARGET_ARTIFACT_ALIASES.get(target.lower(), (target.lower(),))}
    wanted.add(target.lower())
    for name in variants:
        lowered = name.lower()
        if any(re.match(re.escape(alias) + r"(api|runtime|sources|metadata|javadoc)", lowered) for alias in wanted):
            return True
    return False


def read_coordinates(path):
    """group:artifact of the fork's column of every row of COORDINATES.md's table."""
    found = []
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            cells = [cell.strip().strip("`") for cell in line.strip().strip("|").split("|")]
            if len(cells) >= 3 and cells[-1].startswith("org.thisisthepy.compose"):
                group, artifact = cells[-1].split(":")[:2]
                found.append((group, artifact))
    return found


def check(bundle, catalog, platforms, coordinates=None, skip=()):
    names = read_platforms(platforms)
    problems = []
    index = bundle_index(bundle)
    skipped = {target.lower() for target in skip}
    own_android = read_android_targets(catalog)

    for library, project, supported, custom in read_catalog(catalog, names):
        group, artifact = fork_coordinate(project)
        where = f"{group}:{artifact} ({library})"
        root = index.get((group, artifact))
        if not root:
            problems.append(f"{where}: the root is not in the bundle")
            continue
        if custom:
            continue
        variants = root_variants(root)
        for platform in sorted(supported):
            target = platform
            if platform == "KotlinMultiplatform":
                continue
            if platform == "Android":
                if project in own_android:
                    continue
                if not any(v.lower().startswith("android") for v in variants):
                    problems.append(f"{where}: the root lists no android variant, which is what points an Android app at androidx")
                continue
            if target.lower() in skipped:
                continue
            aliases = TARGET_ARTIFACT_ALIASES.get(target.lower(), (target.lower(),))
            if project in SPECIAL_JVM_COMPONENTS and target == "Desktop":
                aliases = ("jvm",) + aliases  # the first alias is the one the message names
            if not any((group, f"{artifact}-{alias}") in index for alias in aliases):
                problems.append(f"{where}: no {artifact}-{aliases[0]} component for {target}, which upstream publishes")
            elif not lists_target(variants, target) and not (project in SPECIAL_JVM_COMPONENTS and lists_target(variants, "jvm")):
                problems.append(f"{where}: {artifact}-{aliases[0]} is in the bundle but the root has no {target} variant for a consumer to find it by")

    if coordinates:
        for group, artifact in read_coordinates(coordinates):
            if (group, artifact) not in index:
                problems.append(f"{group}:{artifact}: listed in COORDINATES.md, and not in the bundle")
    return problems


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("bundle")
    parser.add_argument("--catalog", required=True)
    parser.add_argument("--platforms", required=True)
    parser.add_argument("--coordinates")
    parser.add_argument("--skip-target", action="append", default=[])
    args = parser.parse_args()
    problems = check(args.bundle, args.catalog, args.platforms, args.coordinates, args.skip_target)
    for problem in problems:
        print(f"FAIL  {problem}")
    print(f"{len(problems)} gap(s) between {args.bundle} and what upstream publishes")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
