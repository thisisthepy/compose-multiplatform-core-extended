#!/usr/bin/env python3
"""Fails when a release would leave out a target of the Compose UI stack.

Usage: check_skip_targets.py <extended-publish.yml>

This fork is a drop-in replacement for upstream Compose Multiplatform (#325), so a release on
Maven Central carries every target upstream publishes the UI stack for: the Skiko platforms,
ComposePlatforms.SKIKO_SUPPORT in buildSrc (desktop, macosArm64, iosArm64, iosSimulatorArm64,
js and wasmJs). CENTRAL_SKIP_TARGETS in the publish workflow names targets that are built and
left out of the bundle (central_bundle.py stage --skip-target); it may name others, but none of
those. 1.11.1-ext.6 went out without js this way, and every Kotlin/JS consumer got "No matching
variant".

The workflow's top-level `env:` is read by indentation, without a YAML library, the way
check_publish_triggers.py reads these files: `CENTRAL_SKIP_TARGETS: a b` on one line or a
`>-` / `|` block under it. Exits 1 naming each such target, or when the variable is not there.
"""

import re
import sys

# ComposePlatforms.SKIKO_SUPPORT (buildSrc/public/.../ComposePlatforms.kt), by the target names
# the workflow uses: KotlinMultiplatform + JVM_BASED + IOS + MACOS_NATIVE + WEB, Android aside,
# which the fork does not publish (the roots point it at androidx).
UI_STACK_TARGETS = ("desktop", "jvm", "macosArm64", "iosArm64", "iosSimulatorArm64", "js", "wasmJs")

ENTRY = re.compile(r"^(\s+)CENTRAL_SKIP_TARGETS:\s*(.*?)\s*$")


def skip_targets(text):
    """The targets CENTRAL_SKIP_TARGETS names in the top-level env, or None when it has none."""
    lines = text.splitlines()
    in_env = False
    for i, line in enumerate(lines):
        if re.match(r"^env:\s*$", line):
            in_env = True
            continue
        if in_env and line and not line[0].isspace() and not line.startswith("#"):
            in_env = False
        if not in_env:
            continue
        m = ENTRY.match(line)
        if not m:
            continue
        key_indent = len(m.group(1))
        value = m.group(2)
        if value and value[0] not in ">|":
            return value.strip("'\"").split()
        words = []
        for following in lines[i + 1:]:
            stripped = following.strip()
            if stripped and len(following) - len(following.lstrip(" ")) <= key_indent:
                break
            if stripped.startswith("#"):
                continue
            words += stripped.split()
        return words
    return None


def check(text):
    targets = skip_targets(text)
    if targets is None:
        return ["CENTRAL_SKIP_TARGETS is not in the workflow's top-level env"]
    wanted = {t.lower() for t in UI_STACK_TARGETS}
    return [
        f"CENTRAL_SKIP_TARGETS leaves out {t}, a target upstream publishes the Compose UI stack for"
        for t in targets
        if t.lower() in wanted
    ]


def main(argv):
    if len(argv) != 2:
        print(__doc__.strip().splitlines()[2], file=sys.stderr)
        return 2
    with open(argv[1], encoding="utf-8") as f:
        problems = check(f.read())
    for problem in problems:
        print(f"{argv[1]}: {problem}", file=sys.stderr)
    if problems:
        return 1
    print(f"{argv[1]}: no target of the Compose UI stack is left out of a release")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
