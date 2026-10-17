# Acceptance checks per format

The source of truth is FR-22.6 in compose-rust (`docs/SPEC.md`, the desktop sample
distribution bundle). This file lists, per format, the checks that criterion implies so
the Compose plugin can run the same ones. If this file and FR-22.6 disagree, FR-22.6 wins
and this file is wrong. Changing what the project promises is done in compose-rust, not here.

## Checks that apply to every format

1. The package holds one executable, the icon and the metadata. Nothing else is required
   to run it.
2. The executable references no absolute path of the build machine.
3. Name, identifier and description in the package equal the app settings. The icon is present.
4. A checksum (SHA-256) of the package is recorded beside it.
5. Rebuilding from the same inputs with the same version yields the same metadata.

## Per format

| Format | Checks |
|---|---|
| `.app` | `Info.plist` is well-formed and has `CFBundleIdentifier`, `CFBundleExecutable` and `CFBundleIconFile`; `NSHighResolutionCapable` is `true`; the executable named by `CFBundleExecutable` exists in `Contents/MacOS` and is executable; `AppIcon.icns` exists in `Contents/Resources`; `codesign --verify` passes (ad hoc signature by default); `plutil -lint` passes |
| `.dmg` | mounts with `hdiutil attach`; contains the `.app` and an `Applications` link; the app inside passes the `.app` checks |
| `.pkg` | `pkgutil --check-signature` reports the signing state (unsigned is allowed unless a certificate is configured); `pkgutil --payload-files` lists the `.app` |
| `.AppImage` | `--appimage-extract` works; `AppRun`, `{{identifier}}.desktop` and the icon are at the root; `desktop-file-validate` passes; the zsync update information is embedded when configured |
| `.deb` | `dpkg-deb --info` shows package name, version and description matching the settings; `dpkg-deb --contents` lists the executable, the `.desktop` file and the icons |
| `.rpm` | `rpm -qpi` shows name, version and summary matching the settings; `rpm -qpl` lists the executable and the `.desktop` file |
| Flatpak | the manifest is valid JSON with `id` equal to the identifier; `appstreamcli validate` passes on the metainfo; `desktop-file-validate` passes; `flatpak-builder` builds it |
| `.exe` installer (Velopack) | `vpk pack` exits 0; the output holds `Setup.exe`, a `*-full.nupkg`, a `releases.*.json` feed and, when a previous release is supplied, a `*-delta.nupkg`; the package id equals the identifier and the version equals the app version; the installed app starts from the Start menu shortcut |
| `.msix` | `AppxManifest.xml` is well-formed; `Identity Name` equals the identifier and `Version` is the four part version; `makeappx pack` succeeds; the four logo assets named in the manifest exist; the Windows App Certification Kit passes on a machine that has it; it is built unsigned and the install instructions (developer mode, or re-sign with a test certificate) ship with it |
| `.msi` | on hold |

## Template checks (run in CI by this repository)

- Every template that is XML is well-formed once placeholders are replaced with sample values.
- Every template contains the placeholders its format needs (identifier, name, version
  and the executable where the format names one).
- No placeholder in a template is missing from `metadata-map.md`.

## Needs a human on real hardware

- Install, launch, update and uninstall of the Windows installer and the `.msix`.
- Gatekeeper behavior of the ad hoc signed `.app` and `.dmg` on a clean Mac.
- The AppImage on a desktop with and without FUSE.
