#!/usr/bin/env python3
"""Fails when a workflow that publishes can be started by a branch push or another automatic event.

Usage: check_publish_triggers.py <workflows dir>

Every upload of this fork's artifacts costs the namespace on Maven Central part of its monthly
file and size allowance, and a version there can never be replaced. So a publish starts only
from a release tag or from a person: `push` limited to `tags`/`tags-ignore`, `workflow_dispatch`,
`workflow_call` and `release`. A push to a branch, a pull request, a schedule, a merge queue or
another workflow's run never starts one.

A workflow publishes when it runs one of the upload paths (PUBLISHING below), or calls a
workflow of this directory that does (`uses: ./.github/workflows/<file>`). The `on:` block is
read by indentation, the way these files are written, without a YAML library: block mappings,
`on: push`, `on: [push, workflow_dispatch]` and `push: {tags: [...]}` are understood.

Exits 1 on any such trigger, naming the file and the event.
"""

import os
import re
import sys

# What uploads somewhere: the Central Portal upload, the HTTP PUT to a Maven repository, and
# the Gradle tasks that publish to a remote repository.
PUBLISHING = (
    re.compile(r"central-upload\.sh"),
    re.compile(r"central_bundle\.py\s+put\b"),
    re.compile(r"central\.sonatype\.com"),
    re.compile(r"\bpublish(?:AndRelease)?ToMavenCentral\b"),
    re.compile(r"\bpublishAllPublicationsTo(?!MavenLocal)\w+Repository\b"),
    re.compile(r"\bmaven\.pkg\.github\.com\b"),
)
ALLOWED_EVENTS = {"workflow_dispatch", "workflow_call", "release"}
TAG_FILTERS = {"tags", "tags-ignore"}
CALL = re.compile(r"^\s*(?:-\s*)?uses:\s*['\"]?\./\.github/workflows/([^'\"\s@]+)", re.M)


def indent(line):
    return len(line) - len(line.lstrip(" "))


def meaningful(line):
    stripped = line.strip()
    return stripped and not stripped.startswith("#")


def flow_items(text):
    """The names in a flow sequence or mapping: `[a, b]` -> a, b; `{a: [x], b: y}` -> a, b."""
    text = text.strip()
    if text.startswith("["):
        return [item.strip().strip("'\"") for item in text.strip("[]").split(",") if item.strip()]
    if text.startswith("{"):
        inner, depth, keys, current = text[1:-1], 0, [], ""
        for char in inner:
            if char in "[{":
                depth += 1
            elif char in "]}":
                depth -= 1
            if char == "," and depth == 0:
                keys.append(current)
                current = ""
            else:
                current += char
        keys.append(current)
        return [key.split(":", 1)[0].strip().strip("'\"") for key in keys if key.strip()]
    return [text.strip("'\"")] if text and text not in ("null", "~") else []


def triggers(text):
    """{event: set of its filter keys} from a workflow's `on:` block (None: no filters given)."""
    lines = text.splitlines()
    for number, line in enumerate(lines):
        match = re.match(r"^(?:on|'on'|\"on\"):\s*(.*?)\s*(?:#.*)?$", line)
        if match:
            break
    else:
        return {}
    inline = match.group(1)
    if inline:
        return {event: None for event in flow_items(inline)}
    block = []
    for line in lines[number + 1:]:
        if meaningful(line) and indent(line) == 0:
            break
        block.append(line)
    events = {}
    body = [line for line in block if meaningful(line)]
    if not body:
        return events
    level = indent(body[0])
    current = None
    child_level = None
    for line in body:
        stripped = line.strip()
        if indent(line) == level:
            if stripped.startswith("- "):
                events[stripped[2:].strip().strip("'\"")] = None
                current = None
                continue
            name, _, value = stripped.partition(":")
            current = name.strip().strip("'\"")
            value = re.sub(r"\s+#.*$", "", value).strip()
            events[current] = set(flow_items(value)) if value.startswith("{") else None
            child_level = None
        elif current is not None and indent(line) > level:
            if child_level is None:
                child_level = indent(line)
            if indent(line) == child_level and not stripped.startswith("- "):
                if events[current] is None:
                    events[current] = set()
                events[current].add(stripped.partition(":")[0].strip().strip("'\""))
    return events


def publishes(directory):
    """The workflow files of <directory> that publish, directly or through a workflow they call."""
    texts = {}
    for name in sorted(os.listdir(directory)):
        if name.endswith((".yml", ".yaml")):
            with open(os.path.join(directory, name), encoding="utf-8") as handle:
                texts[name] = handle.read()
    found = {name for name, text in texts.items() if any(p.search(text) for p in PUBLISHING)}
    changed = True
    while changed:
        changed = False
        for name, text in texts.items():
            if name not in found and any(called in found for called in CALL.findall(text)):
                found.add(name)
                changed = True
    return texts, found


def check(directory):
    texts, found = publishes(directory)
    problems = []
    for name in sorted(found):
        for event, filters in triggers(texts[name]).items():
            if event in ALLOWED_EVENTS:
                continue
            if event == "push" and filters and filters <= TAG_FILTERS:
                continue
            if event == "push":
                why = "a push to a branch starts it" if not filters else \
                    f"its push filters ({', '.join(sorted(filters))}) let a branch push start it"
            else:
                why = f"the {event} event starts it"
            problems.append(f"{name}: publishes, and {why}; publish from a tag or a dispatch only")
    return found, problems


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    found, problems = check(sys.argv[1])
    for problem in problems:
        print(f"FAIL  {problem}")
    for name in sorted(found):
        print(f"publishes  {name}")
    print(f"{len(found)} publishing workflow(s), {len(problems)} trigger problem(s)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
