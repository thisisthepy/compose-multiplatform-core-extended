#!/usr/bin/env python3
"""Unit tests for extended/packaging/scripts/validate-templates.py."""
import importlib.util
import shutil
import tempfile
import unittest
from pathlib import Path

SCRIPT_PATH = Path(__file__).resolve().parent / "validate-templates.py"


def _load_validator():
    spec = importlib.util.spec_from_file_location("validate_templates", SCRIPT_PATH)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Unable to load {SCRIPT_PATH}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


vt = _load_validator()


class ValidateTemplatesTest(unittest.TestCase):
    def setUp(self) -> None:
        self.mapping = (vt.ROOT / "metadata-map.md").read_text(encoding="utf-8")

    def test_repo_templates_pass(self) -> None:
        errors = vt.validate_all()
        self.assertEqual(errors, [])

    def test_wix_template_is_required_and_valid(self) -> None:
        self.assertIn("app.wxs.in", vt.REQUIRED)
        wxs_text = (vt.TEMPLATES / "app.wxs.in").read_text(encoding="utf-8")
        errors = vt.validate_template("app.wxs.in", wxs_text, self.mapping)
        self.assertEqual(errors, [])

    def test_application_manifest_runs_as_invoker_per_monitor_v2(self) -> None:
        manifest = (vt.TEMPLATES / "application.manifest.in").read_text(encoding="utf-8")
        self.assertEqual(vt.validate_template("application.manifest.in", manifest, self.mapping), [])
        elevated = manifest.replace('level="asInvoker"', 'level="requireAdministrator"')
        errors = vt.validate_template("application.manifest.in", elevated, self.mapping)
        self.assertTrue(any("asInvoker" in e for e in errors), f"Expected asInvoker error, got: {errors}")
        system_aware = manifest.replace(">PerMonitorV2<", ">System<")
        errors = vt.validate_template("application.manifest.in", system_aware, self.mapping)
        self.assertTrue(any("PerMonitorV2" in e for e in errors), f"Expected PerMonitorV2 error, got: {errors}")

    def test_appx_identity_name_is_identity_name(self) -> None:
        # The Store may reserve a name other than the identifier; Identity Name follows it.
        appx = (vt.TEMPLATES / "AppxManifest.xml.in").read_text(encoding="utf-8")
        reserved = {**vt.SAMPLE, "identity_name": "ExamplePublisher.Hello"}
        self.assertEqual(vt.validate_template("AppxManifest.xml.in", appx, self.mapping, sample=reserved), [])

    def test_missing_required_placeholder_fails(self) -> None:
        wxs_text = (vt.TEMPLATES / "app.wxs.in").read_text(encoding="utf-8")
        broken = wxs_text.replace("{{upgrade_code}}", "8f14e45f-ea9c-5d6b-9a3f-2c1e4b8a7d6c")
        errors = vt.validate_template("app.wxs.in", broken, self.mapping)
        self.assertTrue(
            any("required placeholder {{upgrade_code}} is absent" in e for e in errors),
            f"Expected missing placeholder error, got: {errors}",
        )

    def test_unknown_placeholder_fails(self) -> None:
        wxs_text = (vt.TEMPLATES / "app.wxs.in").read_text(encoding="utf-8")
        broken = wxs_text.replace("AppIcon.ico", "{{nonexistent_key}}")
        errors = vt.validate_template("app.wxs.in", broken, self.mapping)
        self.assertTrue(
            any("unknown placeholder {{nonexistent_key}}" in e for e in errors),
            f"Expected unknown placeholder error, got: {errors}",
        )

    def test_unexpanded_malformed_placeholder_fails(self) -> None:
        desktop = (vt.TEMPLATES / "app.desktop.in").read_text(encoding="utf-8")
        broken = desktop + "\nX-Extra={{BadCase}}\n"
        errors = vt.validate_template("app.desktop.in", broken, self.mapping)
        self.assertTrue(
            any("unexpanded or malformed placeholder" in e for e in errors),
            f"Expected malformed placeholder error, got: {errors}",
        )

    def test_malformed_xml_fails(self) -> None:
        wxs_text = (vt.TEMPLATES / "app.wxs.in").read_text(encoding="utf-8")
        broken = wxs_text.replace("</Wix>", "")
        errors = vt.validate_template("app.wxs.in", broken, self.mapping)
        self.assertTrue(
            any("not well-formed after rendering" in e for e in errors),
            f"Expected malformed XML error, got: {errors}",
        )

    def test_wix_invalid_upgrade_code_fails(self) -> None:
        wxs_text = (vt.TEMPLATES / "app.wxs.in").read_text(encoding="utf-8")
        bad_sample = dict(vt.SAMPLE)
        bad_sample["upgrade_code"] = "not-a-guid"
        errors = vt.validate_template("app.wxs.in", wxs_text, self.mapping, sample=bad_sample)
        self.assertTrue(
            any("UpgradeCode must be a valid GUID" in e for e in errors),
            f"Expected UpgradeCode GUID error, got: {errors}",
        )

    def test_plist_syntax_and_keys_checked(self) -> None:
        plist_text = (vt.TEMPLATES / "Info.plist.in").read_text(encoding="utf-8")
        broken = plist_text.replace(
            "<key>NSHighResolutionCapable</key><true/>",
            "<key>NSHighResolutionCapable</key><false/>",
        )
        errors = vt.validate_template("Info.plist.in", broken, self.mapping)
        self.assertTrue(
            any("NSHighResolutionCapable must be true" in e for e in errors),
            f"Expected plist check error, got: {errors}",
        )

    def test_desktop_entry_syntax_checked(self) -> None:
        desktop = (vt.TEMPLATES / "app.desktop.in").read_text(encoding="utf-8")
        broken = desktop + "\nInvalidLineWithoutEquals\n"
        errors = vt.validate_template("app.desktop.in", broken, self.mapping)
        self.assertTrue(
            any("not a valid Key=Value entry" in e for e in errors),
            f"Expected desktop syntax error, got: {errors}",
        )

    def test_control_syntax_valid_and_invalid(self) -> None:
        valid_control = (
            "Package: hello-app\n"
            "Version: 1.2.3\n"
            "Architecture: amd64\n"
            "Maintainer: Example <dev@example.com>\n"
            "Description: A hello app\n"
            " Extended description line.\n"
        )
        self.assertEqual(vt.validate_control("control.in", valid_control), [])

        invalid_control = (
            "Package: Invalid_Upper\n"
            "Version: 1.2.3\n"
            "NotAValidLine\n"
        )
        errors = vt.validate_control("control.in", invalid_control)
        self.assertTrue(any("not a valid Debian control field" in e for e in errors))
        self.assertTrue(any("missing required control field Architecture" in e for e in errors))
        self.assertTrue(any("invalid Debian Package name" in e for e in errors))

    def test_missing_template_file_in_directory_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            tmp_templates = Path(tmpdir) / "templates"
            shutil.copytree(vt.TEMPLATES, tmp_templates)
            (tmp_templates / "app.wxs.in").unlink()
            errors = vt.validate_all(templates_dir=tmp_templates)
            self.assertTrue(
                any("app.wxs.in: template is missing" in e for e in errors),
                f"Expected missing template error, got: {errors}",
            )


if __name__ == "__main__":
    unittest.main()
