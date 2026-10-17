#!/usr/bin/env python3
"""Validates extended/packaging/templates: XML well-formed, required placeholders present.

Run with `uv run extended/packaging/scripts/validate-templates.py`. Uses only the
standard library.
"""
import json
import re
import sys
import xml.dom.minidom
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TEMPLATES = ROOT / "templates"

SAMPLE = {
    "name": "Hello &amp; Co",
    "identifier": "dev.example.hello",
    "version": "1.2.3",
    "version4": "1.2.3.0",
    "icon": "icon.png",
    "description": "A hello app",
    "executable": "hello",
    "build": "1",
    "publisher": "Example",
    "publisher_id": "CN=Example",
    "date": "2026-10-05",
    "category_mac": "public.app-category.utilities",
    "category_xdg": "Utility;",
    "license": "LicenseRef-proprietary",
    "runtime_version": "24.08",
    "app_id": "Hello",
    "min_windows": "10.0.17763.0",
    "max_windows_tested": "10.0.26100.0",
    "min_macos": "13.0",
}

REQUIRED = {
    "Info.plist.in": ["identifier", "name", "version", "executable"],
    "app.desktop.in": ["name", "executable", "identifier"],
    "app.metainfo.xml.in": ["identifier", "name", "version", "description", "executable"],
    "flatpak-manifest.json.in": ["identifier", "executable"],
    "AppxManifest.xml.in": ["identifier", "name", "version4", "description", "executable"],
    "application.manifest.in": ["identifier", "version4"],
}

PLACEHOLDER = re.compile(r"\{\{([a-z0-9_]+)\}\}")


def main() -> int:
    errors = []
    mapping = (ROOT / "metadata-map.md").read_text()
    files = sorted(p.name for p in TEMPLATES.iterdir())
    for name in REQUIRED:
        if name not in files:
            errors.append(f"{name}: template is missing")
    for name in files:
        if name not in REQUIRED:
            errors.append(f"{name}: not listed in REQUIRED, add its required placeholders")
            continue
        text = (TEMPLATES / name).read_text()
        found = set(PLACEHOLDER.findall(text))
        for key in REQUIRED[name]:
            if key not in found:
                errors.append(f"{name}: required placeholder {{{{{key}}}}} is absent")
        for key in found:
            if key not in SAMPLE:
                errors.append(f"{name}: unknown placeholder {{{{{key}}}}}")
            elif "{{" + key + "}}" not in mapping:
                errors.append(f"{name}: {{{{{key}}}}} is not documented in metadata-map.md")
        if "—" in text:
            errors.append(f"{name}: contains an em dash")
        rendered = PLACEHOLDER.sub(lambda m: SAMPLE.get(m.group(1), m.group(0)), text)
        try:
            if name.endswith((".xml.in", ".manifest.in")) or name == "Info.plist.in":
                xml.dom.minidom.parseString(rendered.encode("utf-8"))
            elif name.endswith(".json.in"):
                json.loads(rendered)
            elif name == "app.desktop.in":
                if not rendered.startswith("[Desktop Entry]"):
                    errors.append(f"{name}: must start with [Desktop Entry]")
        except Exception as e:  # noqa: BLE001
            errors.append(f"{name}: not well-formed after rendering: {e}")
    for e in errors:
        print("error:", e, file=sys.stderr)
    if not errors:
        print(f"ok: {len(files)} templates")
    return 1 if errors else 0


sys.exit(main())
