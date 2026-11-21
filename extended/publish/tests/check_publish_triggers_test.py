#!/usr/bin/env python3
"""Tests check_publish_triggers.py against this repository's workflows and made-up ones.

Usage: python3 -I extended/publish/tests/check_publish_triggers_test.py

The red one is the shape extended-publish.yml had from 2026-10-17 to 2026-11-14 (push on
`branches: [extended]` beside the release tags, and pull_request): the checker fails it on both
(checked against 0c2e8e4acf5^:.github/workflows/extended-publish.yml).
"""

import os
import shutil
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, ".."))
import check_publish_triggers as cpt  # noqa: E402

WORKFLOWS = os.path.abspath(os.path.join(HERE, "..", "..", "..", ".github", "workflows"))
UPLOAD = """
jobs:
  upload:
    runs-on: ubuntu-24.04
    steps:
      - run: extended/publish/central-upload.sh bundle.zip name USER_MANAGED
"""
BUILD_ONLY = """
jobs:
  build:
    runs-on: ubuntu-24.04
    steps:
      - run: ./kotlin publish mavenLocal
"""


class CheckPublishTriggersTest(unittest.TestCase):
    def setUp(self):
        self.work = tempfile.mkdtemp()

    def tearDown(self):
        shutil.rmtree(self.work)

    def write(self, name, text):
        with open(os.path.join(self.work, name), "w") as handle:
            handle.write(text)

    def problems(self):
        return cpt.check(self.work)[1]

    def test_this_repository_publishes_from_tags_and_dispatches_only(self):
        found, problems = cpt.check(WORKFLOWS)
        self.assertEqual([], problems)
        self.assertIn("extended-publish.yml", found)

    def test_a_push_to_a_branch_fails(self):
        self.write("publish.yml", """name: Publish
on:
  push:
    branches:
    - extended
    tags:
    - v*-ext.*
  workflow_dispatch: {}
""" + UPLOAD)
        problems = self.problems()
        self.assertEqual(1, len(problems), problems)
        self.assertIn("publish.yml: publishes, and its push filters (branches, tags)", problems[0])

    def test_a_tag_push_and_a_dispatch_pass(self):
        self.write("publish.yml", """on:
  push:
    tags:
    - v*-ext.*   # a release
  workflow_dispatch:
    inputs:
      tag:
        description: x
        required: false
  workflow_call: {}
""" + UPLOAD)
        self.assertEqual([], self.problems())

    def test_a_push_without_filters_fails(self):
        for on in ("on: push\n", "on: [push, workflow_dispatch]\n", "on:\n  push:\n", "on:\n  push: {}\n",
                   "on:\n  - push\n", "on:\n  push:\n    paths:\n    - extended/**\n"):
            with self.subTest(on=on):
                self.write("publish.yml", on + UPLOAD)
                problems = self.problems()
                self.assertEqual(1, len(problems), problems)
                self.assertIn("push", problems[0])

    def test_a_flow_mapping_of_tags_passes(self):
        self.write("publish.yml", "on:\n  push: {tags: [v*-ext.*]}\n" + UPLOAD)
        self.assertEqual([], self.problems())

    def test_pull_requests_and_schedules_fail(self):
        for event in ("pull_request", "pull_request_target", "schedule", "merge_group", "workflow_run"):
            with self.subTest(event=event):
                self.write("publish.yml", f"on:\n  {event}:\n    types: [opened]\n  workflow_dispatch: {{}}\n" + UPLOAD)
                problems = self.problems()
                self.assertEqual(1, len(problems), problems)
                self.assertIn(f"the {event} event starts it", problems[0])

    def test_a_workflow_that_does_not_publish_may_run_on_push(self):
        self.write("test.yml", "on:\n  push:\n    branches: [extended]\n  pull_request: {}\n" + BUILD_ONLY)
        self.assertEqual([], self.problems())

    def test_a_caller_of_a_publishing_workflow_publishes(self):
        self.write("publish.yml", "on:\n  workflow_call: {}\n" + UPLOAD)
        self.write("ci.yml", """on:
  push:
    branches: [extended]
jobs:
  publish:
    uses: ./.github/workflows/publish.yml
    secrets: inherit
""")
        problems = self.problems()
        self.assertEqual(1, len(problems), problems)
        self.assertIn("ci.yml: publishes, and its push filters (branches)", problems[0])

    def test_every_upload_path_counts_as_publishing(self):
        for step in ("python3 -I extended/publish/central_bundle.py put dev https://maven.pkg.github.com/o/r",
                     "curl https://central.sonatype.com/api/v1/publisher/upload",
                     "./gradlew publishToMavenCentral", "./gradlew publishAndReleaseToMavenCentral",
                     "./gradlew publishAllPublicationsToSonatypeRepository"):
            with self.subTest(step=step):
                self.write("publish.yml", f"on: push\njobs:\n  a:\n    steps:\n      - run: {step}\n")
                self.assertEqual(1, len(self.problems()))
        self.write("publish.yml", "on: push\njobs:\n  a:\n    steps:\n      - run: ./gradlew publishAllPublicationsToMavenLocalRepository\n")
        self.assertEqual([], self.problems())


if __name__ == "__main__":
    unittest.main()
