# Coordinates

What this fork publishes, under which coordinates, and at which version. Nothing has been
published to a public repository yet; the table is what a build of `extended` produces.
`compose/publish-local.sh` publishes what an application draws with to the local Maven
repository, and `compose/check-local.py` checks what it left there.

## Groups

Everything this fork publishes goes out under `org.thisisthepy.compose`, never under a
JetBrains group. An artifact under `org.jetbrains.compose` that JetBrains did not build is one
a consumer cannot tell from the real thing, and whichever of the two has the higher version
silently replaces the other.

| JetBrains group | This fork |
|---|---|
| `org.jetbrains.compose.<group>` | `org.thisisthepy.compose.<group>` |
| `org.jetbrains.androidx.<library>` | `org.thisisthepy.compose.androidx.<library>` |
| `org.jetbrains.compose.annotation-internal` | `org.thisisthepy.compose.annotation-internal` |
| `org.jetbrains.compose.collection-internal` | `org.thisisthepy.compose.collection-internal` |

The libraries JetBrains publishes as `org.jetbrains.androidx.*` keep `androidx` in their
group so that the mapping is mechanical in both directions, and so that `window`,
`navigation` or `savedstate` can never collide with a Compose group of the same name.

Artifact names do not change. The groups are set in one place,
`JetBrainsPublication.mavenGroupFor` in `buildSrc`, and everything else follows from it:
publications, POMs, Gradle module metadata and klib unique names. Each library's
`artifactRedirection.groupIdReplacement` (in `compose/`, `lifecycle/`, `navigation/`,
`navigation3/`, `navigationevent/`, `savedstate/`, `window/` and `performance/`) turns the
fork's group back into the androidx group the Android variant points at.
`buildSrc-tests/.../JetBrainsPublicationTest.kt` checks the groups and the replacements.

## Versions

Everything this fork ships is on one version line, so a consumer pins one set: every library
line in `gradle.properties`, the window modules, skiko-awt and the Gradle plugin of
thisisthepy/compose-multiplatform-extended all publish as `<Compose Multiplatform version>-ext.<N>`,
today `1.11.1-ext.2`.

- `N` counts this fork's releases on that Compose, from 1. A release raises it everywhere in
  one commit, and the commit is tagged.
- A local build that is not a release passes `-Pextended.publication.dev=true` and publishes
  `-ext.<N>-dev`, so it can never shadow the release of the same number in a local repository.
- A version without the suffix is refused when the build is configured.
- The upstream version each line's sources are is kept in `gradle.properties`
  (`extended.upstream.version.<LINE>`) and written into every published POM's description
  ("Upstream version: 1.11.0-alpha07 (COMPOSE_MATERIAL3 line)."). It is what JetBrains published
  the same sources as in Compose Multiplatform 1.11.1; for Navigation, SavedState and
  WindowManager, which are not the sources JetBrains published for 1.11.1, it is the androidx
  version they are copied from. The build reads a line's version with that upstream meaning, so
  Material 3 is still an alpha to every check that asks.

`extended/publish-local-all.sh` publishes all of it to the local Maven repository at that
version, the plugin included.

## Every artifact

Each row is a module's root coordinate. Its targets are published beside it as
`<artifact>-<target>` in the same group and version (`ui-desktop`, `ui-macosarm64`,
`ui-iosarm64`, `ui-js`, `ui-wasm-js`, `ui-linuxx64` and so on), and `desktop` also as
`desktop-jvm-<os>-<arch>` for the six desktop systems. Android variants are not published
by this fork: the root metadata points them at the androidx artifact.

Which targets a module has is what upstream publishes it for, `supportedPlatforms` in
`buildSrc/.../JetBrainsPublication.kt` (the Skiko platforms by default: desktop, macosArm64,
iosArm64, iosSimulatorArm64, js and wasmJs; every platform, tvOS, watchOS, Linux and mingwX64
included, for the runtime, lifecycle, savedstate and the like). The fork's Compose has no
`iosX64` or `macosX64` target, and so no such artifact. Beyond that list the fork builds
linuxX64 and linuxArm64 for the modules it always has. `extended/publish/check_targets.py`
reads the catalogue and this table and fails the publish workflow when the bundle lacks a root,
a target's component, the root's variant for it, or a row of this table.

| Line | JetBrains, Compose Multiplatform 1.11.1 | This fork |
|---|---|---|
| `COMPOSE` | `org.jetbrains.compose.annotation-internal:annotation:1.11.1` | `org.thisisthepy.compose.annotation-internal:annotation:1.11.1-ext.2` |
| `COMPOSE` | `org.jetbrains.compose.collection-internal:collection:1.11.1` | `org.thisisthepy.compose.collection-internal:collection:1.11.1-ext.2` |
| `COMPOSE` | `org.jetbrains.compose.animation:animation:1.11.1` | `org.thisisthepy.compose.animation:animation:1.11.1-ext.2` |
| `COMPOSE` | `org.jetbrains.compose.animation:animation-core:1.11.1` | `org.thisisthepy.compose.animation:animation-core:1.11.1-ext.2` |
| `COMPOSE` | `org.jetbrains.compose.animation:animation-graphics:1.11.1` | `org.thisisthepy.compose.animation:animation-graphics:1.11.1-ext.2` |
| `COMPOSE` | `org.jetbrains.compose.foundation:foundation:1.11.1` | `org.thisisthepy.compose.foundation:foundation:1.11.1-ext.2` |
| `COMPOSE` | `org.jetbrains.compose.foundation:foundation-layout:1.11.1` | `org.thisisthepy.compose.foundation:foundation-layout:1.11.1-ext.2` |
| `COMPOSE` | `org.jetbrains.compose.material:material:1.11.1` | `org.thisisthepy.compose.material:material:1.11.1-ext.2` |
| `COMPOSE` | `org.jetbrains.compose.material:material-navigation:1.11.1` | `org.thisisthepy.compose.material:material-navigation:1.11.1-ext.2` |
| `COMPOSE` | `org.jetbrains.compose.material:material-ripple:1.11.1` | `org.thisisthepy.compose.material:material-ripple:1.11.1-ext.2` |
| `COMPOSE` | `org.jetbrains.compose.runtime:runtime:1.11.1` | `org.thisisthepy.compose.runtime:runtime:1.11.1-ext.2` |
| `COMPOSE` | `org.jetbrains.compose.runtime:runtime-saveable:1.11.1` | `org.thisisthepy.compose.runtime:runtime-saveable:1.11.1-ext.2` |
| `COMPOSE` | `org.jetbrains.compose.ui:ui:1.11.1` | `org.thisisthepy.compose.ui:ui:1.11.1-ext.2` |
| `COMPOSE` | `org.jetbrains.compose.ui:ui-geometry:1.11.1` | `org.thisisthepy.compose.ui:ui-geometry:1.11.1-ext.2` |
| `COMPOSE` | `org.jetbrains.compose.ui:ui-backhandler:1.11.1` | `org.thisisthepy.compose.ui:ui-backhandler:1.11.1-ext.2` |
| `COMPOSE` | `org.jetbrains.compose.ui:ui-graphics:1.11.1` | `org.thisisthepy.compose.ui:ui-graphics:1.11.1-ext.2` |
| `COMPOSE` | `org.jetbrains.compose.ui:ui-test:1.11.1` | `org.thisisthepy.compose.ui:ui-test:1.11.1-ext.2` |
| `COMPOSE` | `org.jetbrains.compose.ui:ui-test-junit4:1.11.1` | `org.thisisthepy.compose.ui:ui-test-junit4:1.11.1-ext.2` |
| `COMPOSE` | `org.jetbrains.compose.ui:ui-text:1.11.1` | `org.thisisthepy.compose.ui:ui-text:1.11.1-ext.2` |
| `COMPOSE` | `org.jetbrains.compose.ui:ui-tooling:1.11.1` | `org.thisisthepy.compose.ui:ui-tooling:1.11.1-ext.2` |
| `COMPOSE` | `org.jetbrains.compose.ui:ui-tooling-data:1.11.1` | `org.thisisthepy.compose.ui:ui-tooling-data:1.11.1-ext.2` |
| `COMPOSE` | `org.jetbrains.compose.ui:ui-tooling-preview:1.11.1` | `org.thisisthepy.compose.ui:ui-tooling-preview:1.11.1-ext.2` |
| `COMPOSE` | `org.jetbrains.compose.ui:ui-uikit:1.11.1` | `org.thisisthepy.compose.ui:ui-uikit:1.11.1-ext.2` |
| `COMPOSE` | `org.jetbrains.compose.ui:ui-unit:1.11.1` | `org.thisisthepy.compose.ui:ui-unit:1.11.1-ext.2` |
| `COMPOSE` | `org.jetbrains.compose.ui:ui-util:1.11.1` | `org.thisisthepy.compose.ui:ui-util:1.11.1-ext.2` |
| `COMPOSE` | `org.jetbrains.compose.desktop:desktop:1.11.1` | `org.thisisthepy.compose.desktop:desktop:1.11.1-ext.2` |
| `COMPOSE_MATERIAL3` | `org.jetbrains.compose.material3:material3:1.11.0-alpha07` | `org.thisisthepy.compose.material3:material3:1.11.1-ext.2` |
| `COMPOSE_MATERIAL3` | `org.jetbrains.compose.material3:material3-window-size-class:1.11.0-alpha07` | `org.thisisthepy.compose.material3:material3-window-size-class:1.11.1-ext.2` |
| `COMPOSE_MATERIAL3` | `org.jetbrains.compose.material3:material3-adaptive-navigation-suite:1.11.0-alpha07` | `org.thisisthepy.compose.material3:material3-adaptive-navigation-suite:1.11.1-ext.2` |
| `COMPOSE_MATERIAL3_ADAPTIVE` | `org.jetbrains.compose.material3.adaptive:adaptive:1.3.0-alpha07` | `org.thisisthepy.compose.material3.adaptive:adaptive:1.11.1-ext.2` |
| `COMPOSE_MATERIAL3_ADAPTIVE` | `org.jetbrains.compose.material3.adaptive:adaptive-layout:1.3.0-alpha07` | `org.thisisthepy.compose.material3.adaptive:adaptive-layout:1.11.1-ext.2` |
| `COMPOSE_MATERIAL3_ADAPTIVE` | `org.jetbrains.compose.material3.adaptive:adaptive-navigation:1.3.0-alpha07` | `org.thisisthepy.compose.material3.adaptive:adaptive-navigation:1.11.1-ext.2` |
| `COMPOSE_MATERIAL3_ADAPTIVE` | `org.jetbrains.compose.material3.adaptive:adaptive-navigation3:1.3.0-alpha07` | `org.thisisthepy.compose.material3.adaptive:adaptive-navigation3:1.11.1-ext.2` |
| `LIFECYCLE` | `org.jetbrains.androidx.lifecycle:lifecycle-common:2.11.0-beta01` | `org.thisisthepy.compose.androidx.lifecycle:lifecycle-common:1.11.1-ext.2` |
| `LIFECYCLE` | `org.jetbrains.androidx.lifecycle:lifecycle-runtime:2.11.0-beta01` | `org.thisisthepy.compose.androidx.lifecycle:lifecycle-runtime:1.11.1-ext.2` |
| `LIFECYCLE` | `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel:2.11.0-beta01` | `org.thisisthepy.compose.androidx.lifecycle:lifecycle-viewmodel:1.11.1-ext.2` |
| `LIFECYCLE` | `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-savedstate:2.11.0-beta01` | `org.thisisthepy.compose.androidx.lifecycle:lifecycle-viewmodel-savedstate:1.11.1-ext.2` |
| `LIFECYCLE` | `org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.11.0-beta01` | `org.thisisthepy.compose.androidx.lifecycle:lifecycle-runtime-compose:1.11.1-ext.2` |
| `LIFECYCLE` | `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0-beta01` | `org.thisisthepy.compose.androidx.lifecycle:lifecycle-viewmodel-compose:1.11.1-ext.2` |
| `LIFECYCLE` | `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-navigation3:2.11.0-beta01` | `org.thisisthepy.compose.androidx.lifecycle:lifecycle-viewmodel-navigation3:1.11.1-ext.2` |
| `NAVIGATION` | `org.jetbrains.androidx.navigation:navigation-compose:2.9.2` | `org.thisisthepy.compose.androidx.navigation:navigation-compose:1.11.1-ext.2` |
| `NAVIGATION` | `org.jetbrains.androidx.navigation:navigation-common:2.9.2` | `org.thisisthepy.compose.androidx.navigation:navigation-common:1.11.1-ext.2` |
| `NAVIGATION` | `org.jetbrains.androidx.navigation:navigation-runtime:2.9.2` | `org.thisisthepy.compose.androidx.navigation:navigation-runtime:1.11.1-ext.2` |
| `NAVIGATION_3` | `org.jetbrains.androidx.navigation3:navigation3-ui:1.1.1` | `org.thisisthepy.compose.androidx.navigation3:navigation3-ui:1.11.1-ext.2` |
| `NAVIGATION_EVENT` | `org.jetbrains.androidx.navigationevent:navigationevent-compose:1.1.0` | `org.thisisthepy.compose.androidx.navigationevent:navigationevent-compose:1.11.1-ext.2` |
| `SAVEDSTATE` | `org.jetbrains.androidx.savedstate:savedstate:1.4.0` | `org.thisisthepy.compose.androidx.savedstate:savedstate:1.11.1-ext.2` |
| `SAVEDSTATE` | `org.jetbrains.androidx.savedstate:savedstate-compose:1.4.0` | `org.thisisthepy.compose.androidx.savedstate:savedstate-compose:1.11.1-ext.2` |
| `WINDOW` | `org.jetbrains.androidx.window:window-core:1.5.1` | `org.thisisthepy.compose.androidx.window:window-core:1.11.1-ext.2` |

## What is not renamed, and why

- **skiko.** `extended/skiko` builds skiko for `mingwX64` and as a static archive from
  JetBrains' sources with a patch, into a local repository, under JetBrains' own
  `org.jetbrains.skiko`. It is not published by this fork, and every Compose module asks for
  skiko by that coordinate.
- **skiko-awt** is the exception among the skiko artifacts: the patched jar (the AWT switch) is
  this fork's, `org.thisisthepy.compose.skiko:skiko-awt:1.11.1-ext.2`, from
  `extended/skiko/build-skiko-awt.sh`. Each variant of its Gradle module metadata declares one
  capability, its own coordinate, because Amper accepts no other. To keep JetBrains' skiko-awt
  off a class path, `extended/skiko/repoint-desktop-skiko.py` changes this fork's `*-desktop`
  modules from `org.jetbrains.skiko:skiko` to this skiko-awt, in the local repository and in the
  Central bundle. Kotlin/Native and web modules keep skiko. Its natives are JetBrains'
  `skiko-awt-runtime-*` jars at `0.144.6`, and its packages are skiko's own.
- **Stubs this fork depends on from JetBrains.** Some modules depend on JetBrains artifacts
  at fixed versions that carry no classes and only point at androidx (the runtime, lifecycle,
  savedstate, navigationevent, window, and the internal annotation and collection libraries).
  They cannot duplicate anything, and the klib resolver needs some of them by name, so they
  are left as they are.
- **material-navigation** still depends on JetBrains'
  `org.jetbrains.androidx.navigation:navigation-compose:2.9.2`, and through it on JetBrains'
  Compose UI 1.8.2: a second copy of Compose UI for anyone using it with this fork. Pointing it
  at this fork's navigation-compose makes a stable library depend on an alpha one, which the
  dependency stability check refuses. Still to be decided.

## What it costs

Third-party Compose libraries are built against `org.jetbrains.compose`. Next to this fork
they bring JetBrains' Compose in a second time, which Gradle cannot resolve away because the
groups differ, and their klibs name JetBrains' klib unique names, which this fork's klibs do
not answer to. Anything used with this fork has to be built against it.
