# Format table

Owner decision, 2026-10-05. This is the one table the Compose plugin
(`nativeDistributions`) and compose-rust share. The existing upstream formats keep working;
the table adds to them.

| OS | App | Package | Store package | Updates |
|---|---|---|---|---|
| macOS | `.app` | `.dmg` | `.pkg` | on hold |
| Linux | `.AppImage` | `.deb`, `.rpm` | Flatpak | on hold |
| Windows | `.exe` | `.msi` (on hold), `.exe` installer (Velopack) | `.msix` | Velopack |

Notes:

- The Windows installer `.exe` is built with Velopack (`vpk pack`): `Setup.exe` plus the
  full and delta release packages. There is no NSIS. The `.msi` (WiX) is on hold.
- `.pkg`, `.msix` and Flatpak are store formats. They are documented for store
  submission, or for use when a certificate exists. `.msi`, `.exe`, `.dmg` and the plain
  `.app` install unsigned and are the default distribution path.
- `.rpm` is produced by `dx bundle` on the Rust side and by the JVM packager on the
  Compose plugin side. `.deb` likewise (`cargo-packager` also produces it).
- The CLI plugin (no Compose) produces `.kexe` / `.exe` only and does no packaging.
- A packaged app holds one executable plus the icon and metadata (FR-22.6 in compose-rust).
- Velopack has no manifest file. `vpk pack` takes the metadata as command line arguments
  (see `metadata-map.md`), so there is no Velopack template.
