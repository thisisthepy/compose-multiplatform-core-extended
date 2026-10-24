#!/usr/bin/env python3
"""Tests check_targets.py against the real catalogue and made-up bundles.

Usage: python3 -I extended/publish/tests/check_targets_test.py

The first test is the red one: a bundle with what release 1.11.1-ext.1 held (the roots, desktop,
macosArm64, linuxX64 and linuxArm64, and nothing of lifecycle, navigation, savedstate or window)
must fail, naming the iOS, tvOS, watchOS, JavaScript and Wasm targets and the libraries.
"""

import os
import shutil
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, ".."))
import check_targets as ct  # noqa: E402

ROOT = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
BUILD = os.path.join(ROOT, "buildSrc", "public", "src", "main", "kotlin", "org", "jetbrains", "androidx", "build")
CATALOG = os.path.join(BUILD, "JetBrainsPublication.kt")
PLATFORMS = os.path.join(BUILD, "ComposePlatforms.kt")
COORDINATES = os.path.join(ROOT, "extended", "COORDINATES.md")
V = "1.11.1-ext.1"
RELEASED = {"Desktop", "MacosArm64", "LinuxX64", "LinuxArm64"}


def write(path, text=""):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as handle:
        handle.write(text)


def component(bundle, group, artifact, variants=()):
    directory = os.path.join(bundle, *group.split("."), artifact, V)
    write(os.path.join(directory, f"{artifact}-{V}.pom"), "<project/>")
    if variants:
        import json
        write(os.path.join(directory, f"{artifact}-{V}.module"),
              json.dumps({"variants": [{"name": name} for name in variants]}))


def suffix(target):
    """The artifact suffix; lifecycle-common (a jvm() module) is made special by the caller."""
    return {"Desktop": "desktop", "WasmJs": "wasm-js"}.get(target, target.lower())


def make_bundle(bundle, only=None, without=(), no_variants_for=()):
    """Every module of the catalogue, for the targets in `only` (all of them when None)."""
    names = ct.read_platforms(PLATFORMS)
    for _, project, supported, custom in ct.read_catalog(CATALOG, names):
        group, artifact = ct.fork_coordinate(project)
        if any(project.endswith(w) for w in without):
            continue
        targets = [t for t in sorted(supported) if t != "KotlinMultiplatform"]
        built = [t for t in targets if t != "Android" and (only is None or t in only)]
        own_android = ct.read_android_targets(CATALOG)
        variants = [("android" if t == "Android" else t[0].lower() + t[1:]) + "ApiElements-published"
                    for t in targets if (t == "Android" and project not in own_android) or t in built]
        if project in no_variants_for:
            variants = []
        component(bundle, group, artifact, variants)
        if not custom:
            for target in built:
                name = "jvm" if target == "Desktop" and project in ct.SPECIAL_JVM_COMPONENTS else suffix(target)
                component(bundle, group, f"{artifact}-{name}")


class CheckTargets(unittest.TestCase):
    def setUp(self):
        self.work = tempfile.mkdtemp(prefix="check-targets.", dir=os.environ.get("RUNNER_TEMP") or HERE)
        self.addCleanup(shutil.rmtree, self.work, True)

    def run_check(self, **kwargs):
        make_bundle(self.work, **kwargs)
        return ct.check(self.work, CATALOG, PLATFORMS, COORDINATES)

    def test_catalogue_is_read(self):
        names = ct.read_platforms(PLATFORMS)
        paths = {project: supported for _, project, supported, _ in ct.read_catalog(CATALOG, names)}
        self.assertIn(":lifecycle:lifecycle-common", paths)
        self.assertNotIn("Android", paths[":lifecycle:lifecycle-common"])
        self.assertEqual({"IosArm64", "IosSimulatorArm64"}, paths[":compose:ui:ui-uikit"])
        self.assertNotIn("MingwX64", paths[":navigation:navigation-common"])
        self.assertIn("MingwX64", paths[":savedstate:savedstate"])
        self.assertIn("WasmJs", paths[":compose:ui:ui"])
        self.assertEqual({":compose:ui:ui-backhandler"}, ct.read_android_targets(CATALOG))
        self.assertNotIn("LinuxX64", paths[":compose:ui:ui"])

    def test_release_1_11_1_ext_1_fails(self):
        problems = self.run_check(
            only=RELEASED, without=("lifecycle-common", "lifecycle-runtime", "lifecycle-viewmodel",
                                    "lifecycle-viewmodel-savedstate", "lifecycle-runtime-compose",
                                    "lifecycle-viewmodel-compose", "lifecycle-viewmodel-navigation3",
                                    "navigation-compose", "navigation-common", "navigation-runtime",
                                    "navigation3-ui", "navigationevent-compose", "savedstate",
                                    "savedstate-compose", "window-core"))
        text = "\n".join(problems)
        for gap in ("ui-iosarm64", "ui-iossimulatorarm64", "ui-js", "ui-wasm-js", "runtime-tvosarm64",
                    "runtime-watchosarm64", "runtime-mingwx64", "androidx.lifecycle:lifecycle-common",
                    "androidx.navigation:navigation-compose", "androidx.savedstate:savedstate",
                    "androidx.window:window-core"):
            self.assertIn(gap, text)

    def test_complete_bundle_passes(self):
        self.assertEqual([], self.run_check())

    def test_missing_target_is_named(self):
        make_bundle(self.work)
        shutil.rmtree(os.path.join(self.work, "org", "thisisthepy", "compose", "ui", "ui-iosarm64"))
        problems = ct.check(self.work, CATALOG, PLATFORMS)
        self.assertEqual(1, len(problems), problems)
        self.assertIn("no ui-iosarm64 component for IosArm64", problems[0])

    def test_root_without_a_variant_is_named(self):
        problems = self.run_check(no_variants_for=(":compose:ui:ui",))
        self.assertTrue(any("ui-js is in the bundle but the root has no Js variant" in p for p in problems), problems)
        self.assertTrue(any("root lists no android variant" in p for p in problems), problems)

    def test_coordinate_listed_in_coordinates_md_is_required(self):
        make_bundle(self.work, without=(":compose:ui:ui-geometry",))
        problems = ct.check(self.work, CATALOG, PLATFORMS, COORDINATES)
        self.assertTrue(any("ui-geometry: listed in COORDINATES.md" in p or "ui-geometry" in p for p in problems), problems)

    def test_skip_target(self):
        make_bundle(self.work, only={"Desktop", "MacosArm64", "IosArm64", "IosSimulatorArm64", "Js", "WasmJs",
                                     "LinuxX64", "LinuxArm64", "TvosArm64", "TvosSimulatorArm64",
                                     "WatchosArm64", "WatchosArm32", "WatchosSimulatorArm64"})
        self.assertTrue(ct.check(self.work, CATALOG, PLATFORMS))
        self.assertEqual([], ct.check(self.work, CATALOG, PLATFORMS, skip=["MingwX64"]))

    def test_dev_versions_count(self):
        make_bundle(self.work)
        for directory, _, files in os.walk(self.work):
            if os.path.basename(directory) == V:
                new = directory + "-dev.0123abc"
                os.rename(directory, new)
                for name in os.listdir(new):
                    os.rename(os.path.join(new, name), os.path.join(new, name.replace(V, V + "-dev.0123abc")))
        self.assertEqual([], ct.check(self.work, CATALOG, PLATFORMS, COORDINATES))


if __name__ == "__main__":
    unittest.main()
