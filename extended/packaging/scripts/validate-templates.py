#!/usr/bin/env python3
"""Validates extended/packaging/templates: placeholder completeness and format checks.

Run with `python3 extended/packaging/scripts/validate-templates.py`. Uses only the
standard library.
"""
import html
import json
import plistlib
import re
import sys
import xml.dom.minidom
import xml.etree.ElementTree as ET
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
    "upgrade_code": "8f14e45f-ea9c-5d6b-9a3f-2c1e4b8a7d6c",
}

REQUIRED = {
    "Info.plist.in": ["identifier", "name", "version", "executable"],
    "app.desktop.in": ["name", "executable", "identifier"],
    "app.metainfo.xml.in": ["identifier", "name", "version", "description", "executable"],
    "flatpak-manifest.json.in": ["identifier", "executable"],
    "AppxManifest.xml.in": ["identifier", "name", "version4", "description", "executable"],
    "application.manifest.in": ["identifier", "version4"],
    "app.wxs.in": [
        "identifier",
        "name",
        "version",
        "description",
        "executable",
        "publisher",
        "upgrade_code",
    ],
}

PLACEHOLDER = re.compile(r"\{\{([a-z0-9_]+)\}\}")
VERSION4_RE = re.compile(r"^\d+\.\d+\.\d+\.\d+$")
GUID_RE = re.compile(
    r"^\{?[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\}?$"
)
DESKTOP_KEY_RE = re.compile(r"^[A-Za-z0-9-]+(\[[^\]]+\])?$")
CONTROL_FIELD_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9-]*\s*:(.*)$")


def _local_name(tag: str) -> str:
    if "}" in tag:
        return tag.split("}", 1)[1]
    return tag


def _find_by_local(root: ET.Element, local_name: str) -> list[ET.Element]:
    return [el for el in root.iter() if _local_name(el.tag) == local_name]


def validate_plist(name: str, rendered: str, sample: dict[str, str]) -> list[str]:
    errors: list[str] = []
    data = plistlib.loads(rendered.encode("utf-8"))
    if not isinstance(data, dict):
        return [f"{name}: root plist value must be a dict"]
    if data.get("CFBundleIdentifier") != sample["identifier"]:
        errors.append(f"{name}: CFBundleIdentifier does not match identifier")
    if data.get("CFBundleExecutable") != sample["executable"]:
        errors.append(f"{name}: CFBundleExecutable does not match executable")
    if data.get("CFBundleShortVersionString") != sample["version"]:
        errors.append(f"{name}: CFBundleShortVersionString does not match version")
    if not data.get("CFBundleIconFile"):
        errors.append(f"{name}: CFBundleIconFile must be non-empty")
    if data.get("NSHighResolutionCapable") is not True:
        errors.append(f"{name}: NSHighResolutionCapable must be true")
    return errors


def validate_desktop(name: str, rendered: str, sample: dict[str, str]) -> list[str]:
    errors: list[str] = []
    lines = rendered.splitlines()
    if not lines or lines[0].strip() != "[Desktop Entry]":
        return [f"{name}: must start with [Desktop Entry]"]
    section = "[Desktop Entry]"
    entries: dict[str, dict[str, str]] = {section: {}}
    for lineno, raw in enumerate(lines[1:], start=2):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("[") and line.endswith("]"):
            section = line
            entries.setdefault(section, {})
            continue
        if "=" not in line:
            errors.append(f"{name}: line {lineno} is not a valid Key=Value entry")
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        if not key or not DESKTOP_KEY_RE.match(key):
            errors.append(f"{name}: line {lineno} has invalid key '{key}'")
            continue
        if key in entries[section]:
            errors.append(f"{name}: duplicate key '{key}' in {section}")
            continue
        entries[section][key] = value.strip()

    main_section = entries["[Desktop Entry]"]
    if main_section.get("Type") != "Application":
        errors.append(f"{name}: Type must be Application")
    for required_key in ("Name", "Exec", "Icon"):
        if not main_section.get(required_key):
            errors.append(f"{name}: missing required desktop entry key {required_key}")
    if main_section.get("Exec") and main_section["Exec"] != sample["executable"]:
        errors.append(f"{name}: Exec does not match executable")
    if main_section.get("Icon") and main_section["Icon"] != sample["identifier"]:
        errors.append(f"{name}: Icon does not match identifier")
    categories = main_section.get("Categories")
    if categories is not None and not categories.endswith(";"):
        errors.append(f"{name}: Categories must end with ';'")
    return errors


def validate_control(name: str, rendered: str) -> list[str]:
    errors: list[str] = []
    fields: dict[str, str] = {}
    current_field: str | None = None
    for lineno, raw in enumerate(rendered.splitlines(), start=1):
        if not raw.strip():
            continue
        if raw[0] in (" ", "\t"):
            if current_field is None:
                errors.append(f"{name}: line {lineno} continuation line without field")
            else:
                fields[current_field] += "\n" + raw
            continue
        match = CONTROL_FIELD_RE.match(raw)
        if not match:
            errors.append(f"{name}: line {lineno} is not a valid Debian control field")
            continue
        field_name = raw.split(":", 1)[0].strip()
        value = match.group(1).strip()
        if field_name in fields:
            errors.append(f"{name}: duplicate control field '{field_name}'")
            continue
        fields[field_name] = value
        current_field = field_name

    for required_field in ("Package", "Version", "Architecture", "Description"):
        if not fields.get(required_field):
            errors.append(f"{name}: missing required control field {required_field}")
    package = fields.get("Package", "")
    if package and not re.match(r"^[a-z0-9][a-z0-9+.-]+$", package):
        errors.append(f"{name}: invalid Debian Package name '{package}'")
    return errors


def validate_metainfo(name: str, rendered: str, sample: dict[str, str]) -> list[str]:
    errors: list[str] = []
    root = ET.fromstring(rendered.encode("utf-8"))
    if _local_name(root.tag) != "component":
        return [f"{name}: root element must be <component>"]
    if root.attrib.get("type") != "desktop-application":
        errors.append(f"{name}: <component> type must be desktop-application")
    ids = _find_by_local(root, "id")
    if not ids or (ids[0].text or "").strip() != sample["identifier"]:
        errors.append(f"{name}: <id> must equal identifier")
    binaries = _find_by_local(root, "binary")
    if not binaries or (binaries[0].text or "").strip() != sample["executable"]:
        errors.append(f"{name}: <provides>/<binary> must equal executable")
    releases = _find_by_local(root, "release")
    if not releases or releases[0].attrib.get("version") != sample["version"]:
        errors.append(f"{name}: <release> version must equal version")
    return errors


def validate_flatpak_json(name: str, rendered: str, sample: dict[str, str]) -> list[str]:
    errors: list[str] = []
    data = json.loads(rendered)
    if not isinstance(data, dict):
        return [f"{name}: root JSON value must be an object"]
    if data.get("id") != sample["identifier"]:
        errors.append(f"{name}: id does not match identifier")
    if data.get("command") != sample["executable"]:
        errors.append(f"{name}: command does not match executable")
    modules = data.get("modules")
    if not isinstance(modules, list) or not modules:
        errors.append(f"{name}: modules must be a non-empty list")
    return errors


def validate_appx_manifest(name: str, rendered: str, sample: dict[str, str]) -> list[str]:
    errors: list[str] = []
    root = ET.fromstring(rendered.encode("utf-8"))
    if _local_name(root.tag) != "Package":
        return [f"{name}: root element must be <Package>"]
    identities = _find_by_local(root, "Identity")
    if not identities:
        errors.append(f"{name}: missing <Identity> element")
    else:
        identity = identities[0]
        if identity.attrib.get("Name") != sample["identifier"]:
            errors.append(f"{name}: Identity Name does not match identifier")
        version = identity.attrib.get("Version", "")
        if version != sample["version4"] or not VERSION4_RE.match(version):
            errors.append(f"{name}: Identity Version must be a 4-part version matching version4")
    apps = _find_by_local(root, "Application")
    if not apps or apps[0].attrib.get("Executable") != sample["executable"]:
        errors.append(f"{name}: Application Executable does not match executable")
    return errors


def validate_application_manifest(name: str, rendered: str, sample: dict[str, str]) -> list[str]:
    errors: list[str] = []
    root = ET.fromstring(rendered.encode("utf-8"))
    if _local_name(root.tag) != "assembly":
        return [f"{name}: root element must be <assembly>"]
    identities = _find_by_local(root, "assemblyIdentity")
    if not identities:
        errors.append(f"{name}: missing <assemblyIdentity> element")
    else:
        identity = identities[0]
        if identity.attrib.get("name") != sample["identifier"]:
            errors.append(f"{name}: assemblyIdentity name does not match identifier")
        version = identity.attrib.get("version", "")
        if version != sample["version4"] or not VERSION4_RE.match(version):
            errors.append(f"{name}: assemblyIdentity version must match version4")
    return errors


def validate_wix(name: str, rendered: str, sample: dict[str, str]) -> list[str]:
    errors: list[str] = []
    root = ET.fromstring(rendered.encode("utf-8"))
    if _local_name(root.tag) != "Wix":
        return [f"{name}: root element must be <Wix>"]
    products = _find_by_local(root, "Product")
    if not products:
        products = _find_by_local(root, "Package")
    if not products:
        return [f"{name}: missing <Product> or <Package> element"]
    product = products[0]
    expected_name = html.unescape(sample["name"])
    if product.attrib.get("Name") != expected_name:
        errors.append(f"{name}: Product Name does not match name")
    if product.attrib.get("Version") != sample["version"]:
        errors.append(f"{name}: Product Version does not match version")
    if product.attrib.get("Manufacturer") != html.unescape(sample["publisher"]):
        errors.append(f"{name}: Product Manufacturer does not match publisher")
    upgrade_code = product.attrib.get("UpgradeCode", "")
    if not upgrade_code or not GUID_RE.match(upgrade_code):
        errors.append(f"{name}: Product UpgradeCode must be a valid GUID")

    properties = {
        el.attrib.get("Id"): el.attrib.get("Value", "")
        for el in _find_by_local(root, "Property")
    }
    if not properties.get("ARPPRODUCTICON"):
        errors.append(f"{name}: missing ARPPRODUCTICON property")
    if properties.get("ARPCOMMENTS") != html.unescape(sample["description"]):
        errors.append(f"{name}: ARPCOMMENTS property does not match description")

    files = _find_by_local(root, "File")
    if not any(sample["executable"] in el.attrib.get("Source", "") for el in files):
        errors.append(f"{name}: missing <File> referencing executable")
    return errors


def validate_template(
    name: str,
    text: str,
    mapping: str,
    required_keys: list[str] | None = None,
    sample: dict[str, str] | None = None,
) -> list[str]:
    errors: list[str] = []
    sample_values = SAMPLE if sample is None else sample
    req_keys = REQUIRED.get(name, []) if required_keys is None else required_keys

    found = set(PLACEHOLDER.findall(text))
    for key in req_keys:
        if key not in found:
            errors.append(f"{name}: required placeholder {{{{{key}}}}} is absent")
    for key in found:
        if key not in sample_values:
            errors.append(f"{name}: unknown placeholder {{{{{key}}}}}")
        elif "{{" + key + "}}" not in mapping:
            errors.append(f"{name}: {{{{{key}}}}} is not documented in metadata-map.md")
    if "\u2014" in text:
        errors.append(f"{name}: contains an em dash")

    rendered = PLACEHOLDER.sub(lambda m: sample_values.get(m.group(1), m.group(0)), text)
    if "{{" in rendered or "}}" in rendered:
        errors.append(f"{name}: unexpanded or malformed placeholder remains after rendering")

    try:
        if name.endswith((".xml.in", ".manifest.in", ".wxs.in")) or name == "Info.plist.in":
            xml.dom.minidom.parseString(rendered.encode("utf-8"))
        if name == "Info.plist.in":
            errors.extend(validate_plist(name, rendered, sample_values))
        elif name == "app.desktop.in" or name.endswith(".desktop.in"):
            errors.extend(validate_desktop(name, rendered, sample_values))
        elif name == "app.metainfo.xml.in":
            errors.extend(validate_metainfo(name, rendered, sample_values))
        elif name.endswith(".json.in"):
            errors.extend(validate_flatpak_json(name, rendered, sample_values))
        elif name == "AppxManifest.xml.in":
            errors.extend(validate_appx_manifest(name, rendered, sample_values))
        elif name == "application.manifest.in":
            errors.extend(validate_application_manifest(name, rendered, sample_values))
        elif name.endswith(".wxs.in"):
            errors.extend(validate_wix(name, rendered, sample_values))
        elif name == "control.in" or name.endswith(".control.in"):
            errors.extend(validate_control(name, rendered))
    except Exception as e:  # noqa: BLE001
        errors.append(f"{name}: not well-formed after rendering: {e}")

    return errors


def validate_all(
    templates_dir: Path = TEMPLATES,
    metadata_map_path: Path | None = None,
    required: dict[str, list[str]] | None = None,
    sample: dict[str, str] | None = None,
) -> list[str]:
    errors: list[str] = []
    map_path = (ROOT / "metadata-map.md") if metadata_map_path is None else metadata_map_path
    mapping = map_path.read_text(encoding="utf-8")
    req_map = REQUIRED if required is None else required
    sample_values = SAMPLE if sample is None else sample

    files = sorted(p.name for p in templates_dir.iterdir() if p.is_file())
    for name in req_map:
        if name not in files:
            errors.append(f"{name}: template is missing")
    for name in files:
        if name not in req_map:
            errors.append(f"{name}: not listed in REQUIRED, add its required placeholders")
            continue
        text = (templates_dir / name).read_text(encoding="utf-8")
        errors.extend(
            validate_template(
                name,
                text,
                mapping,
                required_keys=req_map[name],
                sample=sample_values,
            )
        )
    return errors


def main(templates_dir: Path = TEMPLATES, metadata_map_path: Path | None = None) -> int:
    errors = validate_all(templates_dir=templates_dir, metadata_map_path=metadata_map_path)
    for e in errors:
        print("error:", e, file=sys.stderr)
    if not errors:
        count = sum(1 for p in templates_dir.iterdir() if p.is_file())
        print(f"ok: {count} templates")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
