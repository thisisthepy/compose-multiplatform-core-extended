#!/usr/bin/env python3
"""Tests check_skip_targets.py, and runs it on this repository's publish workflow."""

import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))

import check_skip_targets  # noqa: E402

WORKFLOW = os.path.join(HERE, "..", "..", "..", ".github", "workflows", "extended-publish.yml")


def workflow(env):
    return "name: publish\n\non:\n  workflow_dispatch: {}\n\nenv:\n" + env + "\njobs:\n  plan:\n    runs-on: x\n"


class SkipTargetsTest(unittest.TestCase):
    def test_a_folded_block_without_ui_targets_passes(self):
        text = workflow("  # left out\n  CENTRAL_SKIP_TARGETS: >-\n    tvosArm64 watchosArm64\n  OTHER: 1\n")
        self.assertEqual(check_skip_targets.skip_targets(text), ["tvosArm64", "watchosArm64"])
        self.assertEqual(check_skip_targets.check(text), [])

    def test_js_left_out_fails(self):
        text = workflow("  CENTRAL_SKIP_TARGETS: >-\n    tvosArm64 tvosSimulatorArm64 js\n")
        problems = check_skip_targets.check(text)
        self.assertEqual(len(problems), 1)
        self.assertIn("leaves out js", problems[0])

    def test_one_line_value_and_any_case(self):
        text = workflow("  CENTRAL_SKIP_TARGETS: 'WasmJs iosArm64'\n")
        self.assertEqual(len(check_skip_targets.check(text)), 2)

    def test_an_empty_list_passes(self):
        self.assertEqual(check_skip_targets.check(workflow("  CENTRAL_SKIP_TARGETS: ''\n")), [])

    def test_a_missing_variable_fails(self):
        self.assertEqual(len(check_skip_targets.check(workflow("  OTHER: 1\n"))), 1)

    def test_a_job_env_does_not_count(self):
        text = "env:\n  OTHER: 1\njobs:\n  a:\n    env:\n      CENTRAL_SKIP_TARGETS: js\n"
        self.assertIsNone(check_skip_targets.skip_targets(text))

    def test_this_repository_releases_every_ui_target(self):
        with open(WORKFLOW, encoding="utf-8") as f:
            self.assertEqual(check_skip_targets.check(f.read()), [])


if __name__ == "__main__":
    unittest.main()
