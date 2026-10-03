# Design systems

Seven design systems for Compose Multiplatform and the Liquid Glass material, as a
standalone Amper project:

| Module | What it is |
|---|---|
| `core` | The contract every system speaks: colour roles, styles and the `DesignSystem` interface |
| `liquid-glass` | The Liquid Glass material: concentric shapes, glass fills, contrast and reduced transparency |
| `material3` | Material 3, delegating to `androidx.compose.material3` |
| `cupertino` | Cupertino (iOS), implemented here |
| `fluent` | Fluent 2, implemented here |
| `gnome` | GNOME 50 (Adwaita) |
| `breeze` | KDE Breeze |
| `deepin` | Deepin (DDE) |

The project came here from
[compose-rust](https://github.com/darkpyonix/compose-rust), where it lived as
`design-systems/`. It is plain Compose code with nothing Rust specific in it, and it lives
in this fork so that any project built on Compose can use it.

It depends on published Compose artifacts only. It does not depend on the Compose modules
in this repository, nor on any renderer that uses it, and nothing may be added that would
make it do so: that is what lets it be published on its own.

## Build and test

The project carries its own Amper wrapper, so a JDK is all it needs. From this directory:

    ./kotlin build                      # every platform the host can build
    ./kotlin test -p jvm                # tests on the JVM
    ./kotlin build -p iosSimulatorArm64 # one platform: jvm, android, iosArm64,
                                        # iosSimulatorArm64 or wasmJs

On Windows use `kotlin.bat`. The Gradle build at the root of this repository does not see
this project, and this project does not see it.

`.github/workflows/design-systems.yml` builds every platform and runs the tests on each
change under this directory.

## Coming next

This is the project exactly as it left compose-rust: the packages and module names are
unchanged, and each system is a single module. The package rename to the final
`org.thisisthepy.compose.*` layout and the two-layer split (one component library per
system, with an adaptive layer above them that picks one) come with
[darkpyonix/compose-rust#39](https://github.com/darkpyonix/compose-rust/issues/39).
