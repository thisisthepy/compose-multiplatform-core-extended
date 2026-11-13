# Design systems

Design systems for Compose Multiplatform, as a standalone Amper project: six component
systems, and the Liquid Glass material that makes the seventh, Apple's current look:

| Module | What it is | Maven coordinates |
|---|---|---|
| `designsystem` | The contract every system speaks: colour roles, styles and the `DesignSystem` interface | `org.thisisthepy.compose.designsystem:designsystem` |
| `designsystem-liquid-glass` | The Liquid Glass material: concentric shapes, glass fills, contrast and reduced transparency | `org.thisisthepy.compose.designsystem:designsystem-liquid-glass` |
| `designsystem-material3` | Material 3, delegating to `androidx.compose.material3` | `org.thisisthepy.compose.designsystem:designsystem-material3` |
| `designsystem-cupertino` | Cupertino (iOS), implemented here | `org.thisisthepy.compose.designsystem:designsystem-cupertino` |
| `designsystem-fluent` | Fluent 2, implemented here | `org.thisisthepy.compose.designsystem:designsystem-fluent` |
| `designsystem-gnome` | GNOME 50 (Adwaita) | `org.thisisthepy.compose.designsystem:designsystem-gnome` |
| `designsystem-breeze` | KDE Breeze | `org.thisisthepy.compose.designsystem:designsystem-breeze` |
| `designsystem-deepin` | Deepin (DDE) | `org.thisisthepy.compose.designsystem:designsystem-deepin` |

Each directory is named as its artifact (`project.yaml` says why). The Kotlin packages are
unchanged (`org.thisisthepy.compose.designsystem`, `org.thisisthepy.compose.material3` and so on).

The project came here from
[compose-rust](https://github.com/darkpyonix/compose-rust), where it lived as
`design-systems/`. It is plain Compose code with nothing Rust specific in it, and it lives
in this fork so that any project built on Compose can use it.

It depends on published Compose artifacts only: this fork's Compose
(`org.thisisthepy.compose.runtime`, `foundation`, `ui` and `material3`), at the fork's version
line, for every target. Never JetBrains' `org.jetbrains.compose.*`: next to the fork's that would
put the same classes on a consumer's path twice, under two groups no resolver merges. On
Android the fork's roots send a consumer to androidx's Compose. It does not depend on the
Compose sources in this repository, nor on any renderer that uses it, and nothing may be added
that would make it do so: that is what lets it be published on its own.

## Published

Every module is published to Maven Central with the rest of this fork, on its one version line
(`1.11.1-ext.N`, the version in the root `gradle.properties`), for `jvm`, `android`, `iosArm64`,
`iosSimulatorArm64`, `macosArm64`, `linuxX64` and `wasmJs`. A consumer names the root; each target
finds its own component (`<artifact>-jvm`, `-android`, `-iosarm64`, `-iossimulatorarm64`,
`-macosarm64`, `-linuxx64`, `-wasmjs`) through the root's Gradle module metadata:

    implementation("org.thisisthepy.compose.designsystem:designsystem-material3:1.11.1-ext.N")

The version is written in two places here, `version` in `publish.module-template.yaml` (what
is published) and `compose-fork` in `libs.versions.toml` (the fork's Compose it is built
against); a release raises both with `gradle.properties`, and `scripts/check-version.sh` fails
when they differ. The POM fields and the compiler plugin come from that template too.

`../publish-local-all.sh` publishes them to the local Maven repository on macOS, for the targets
it published Compose for; `.github/workflows/extended-publish.yml` builds them for every target
against the Compose the same run built, and puts them in the one Central bundle with it.
`extended/COORDINATES.md` lists them.

## Build and test

The project carries its own Amper wrapper, so a JDK is all it needs. From this directory:

    ./kotlin build                      # every platform the host can build
    ./kotlin test -p jvm                # tests on the JVM
    ./kotlin build -p iosSimulatorArm64 # one platform: jvm, android, iosArm64,
                                        # iosSimulatorArm64, macosArm64, linuxX64 or wasmJs

On Windows use `kotlin.bat`. The Gradle build at the root of this repository does not see
this project, and this project does not see it.

The fork's Compose at the version in `libs.versions.toml` has to be in the local Maven
repository (`../compose/publish-local.sh`) or on Maven Central, for every platform built.
`.github/workflows/design-systems.yml` publishes it per platform, then builds every platform and
runs the tests on each change under this directory.

## Coming next

The packages are the ones the project had in compose-rust, and each system is a single module. The package rename to the final
`org.thisisthepy.compose.*` layout and the two-layer split (one component library per
system, with an adaptive layer above them that picks one) come with
[darkpyonix/compose-rust#39](https://github.com/darkpyonix/compose-rust/issues/39).
