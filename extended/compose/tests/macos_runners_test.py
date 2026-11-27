#!/usr/bin/env python3
"""Counts the macOS arm64 jobs one full CI run (ci.yml, suites: all) asks for, and fails over budget.

GitHub's hosted macOS arm64 runners are short: in fork-train-18 (45610212692) 19 jobs that
asked for one were never given one ("The job was not acquired by Runner of type hosted even
after multiple attempts") and were cancelled after one to two hours of queueing, with no step
run. A run asked for 22 of them, every matrix cell counted. A job that only compiles, links or
publishes Apple targets runs on macos-15-intel: Kotlin/Native builds every Apple target from an
x86_64 Mac, and that image has the Xcode the build selects (26.3, .github/actions/setup-xcode).
Only what runs an arm64 binary (a simulator, a macOS test, a window) needs arm64.

So:
- the jobs in COMPILE_ONLY run on macos-15-intel;
- at most ARM64_BUDGET macOS arm64 cells in all. A job that needs one more raises the budget
  here, in the same change, saying why.

Reads ci.yml's `uses: ./.github/workflows/<file>` jobs and each called workflow's jobs, with
`runs-on` taken from the job or, through `matrix.<key>`, from the matrix and its include
entries. Needs PyYAML.
"""

import os
import re
import sys
import unittest

import yaml

HERE = os.path.dirname(os.path.abspath(__file__))
WORKFLOWS = os.path.normpath(os.path.join(HERE, "..", "..", "..", ".github", "workflows"))

ARM64_BUDGET = 19
INTEL = "macos-15-intel"
# (workflow file, job id, matrix cell's platform or None): compile, link or publish only.
COMPILE_ONLY = {
    ("check-public-api.yml", "check-public-api", None),
    ("compose-publish-dry-run.yml", "compose-native-publish", None),
    # The device slice is linked, never run.
    ("design-systems.yml", "build", "iosArm64"),
}


def runners(job):
    """(label, platform) for each cell of a job; platform is the matrix's `platform`, if any."""
    runs_on = job.get("runs-on", "")
    if not isinstance(runs_on, str):
        return [(str(runs_on), None)]
    key = re.search(r"matrix\.([\w-]+)", runs_on)
    if not key:
        return [(runs_on, None)]
    matrix = (job.get("strategy") or {}).get("matrix") or {}
    cells = []
    for value in matrix.get(key.group(1), []) or []:
        cells.append((str(value), None))
    for include in matrix.get("include", []) or []:
        if key.group(1) in include:
            cells.append((str(include[key.group(1)]), include.get("platform")))
    return cells


def macos_cells(workflows=WORKFLOWS):
    """(workflow file, job id, platform, label) for every macOS cell a full CI run asks for."""
    with open(os.path.join(workflows, "ci.yml"), encoding="utf-8") as f:
        ci = yaml.safe_load(f)
    cells = []
    for job in ci["jobs"].values():
        called = re.match(r"\./\.github/workflows/(.+)", job.get("uses", ""))
        if not called:
            continue
        with open(os.path.join(workflows, called.group(1)), encoding="utf-8") as f:
            workflow = yaml.safe_load(f)
        for job_id, inner in (workflow.get("jobs") or {}).items():
            for label, platform in runners(inner):
                if "macos" in label:
                    cells.append((called.group(1), job_id, platform, label))
    return cells


class MacosRunnersTest(unittest.TestCase):
    def test_compile_only_jobs_run_on_intel(self):
        found = {(f, j, p): label for f, j, p, label in macos_cells()}
        for cell in sorted(COMPILE_ONLY, key=str):
            self.assertIn(cell, found, f"{cell} is not a macOS cell of a full CI run")
            self.assertEqual(found[cell], INTEL, f"{cell} only compiles, links or publishes")

    def test_compile_only_jobs_select_the_xcode(self):
        # The Intel image's default Xcode is 16.4, whose iOS SDK lacks what the UIKit code uses
        # (UIViewLayoutRegion, iOS 26): fork-train-20, Design systems (iosArm64).
        for workflow, job_id, _ in sorted(COMPILE_ONLY, key=str):
            with open(os.path.join(WORKFLOWS, workflow), encoding="utf-8") as f:
                job = yaml.safe_load(f)["jobs"][job_id]
            uses = [step.get("uses", "") for step in job.get("steps", [])]
            self.assertIn("./.github/actions/setup-xcode", uses, f"{workflow} {job_id} selects no Xcode")

    def test_setup_xcode_caches_only_on_arm64(self):
        # irgaly/xcode-cache ships its native module for darwin-arm64 only: on Intel it fails
        # with "Cannot find module '../lib/node-v137-darwin-x64/nanoutimes.node'" (fork-train-20).
        action = os.path.join(WORKFLOWS, "..", "actions", "setup-xcode", "action.yml")
        with open(action, encoding="utf-8") as f:
            steps = yaml.safe_load(f)["runs"]["steps"]
        for step in steps:
            if "xcode-cache" in step.get("uses", ""):
                self.assertIn("runner.arch == 'ARM64'", step.get("if", ""), "the DerivedData cache runs on Intel")

    def test_arm64_cells_within_budget(self):
        arm64 = [c for c in macos_cells() if c[3] != INTEL]
        listing = "\n".join(f"  {f} {j} {p or ''} {label}" for f, j, p, label in arm64)
        self.assertLessEqual(len(arm64), ARM64_BUDGET,
                             f"{len(arm64)} macOS arm64 cells, budget {ARM64_BUDGET}:\n{listing}")

    def test_a_matrix_include_is_read(self):
        job = {"runs-on": "${{ matrix.runs-on }}",
               "strategy": {"matrix": {"include": [{"platform": "jvm", "runs-on": "macos-latest"},
                                                   {"platform": "wasmJs", "runs-on": "ubuntu-latest"}]}}}
        self.assertEqual(runners(job), [("macos-latest", "jvm"), ("ubuntu-latest", "wasmJs")])

    def test_a_matrix_list_is_read(self):
        job = {"runs-on": "${{ matrix.os }}", "strategy": {"matrix": {"os": ["macos-15", "macos-latest"]}}}
        self.assertEqual(runners(job), [("macos-15", None), ("macos-latest", None)])


if __name__ == "__main__":
    unittest.main()
