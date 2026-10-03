# Coordinates

What this fork publishes, under which coordinates, and at which version. Nothing has been
published to a public repository yet; the table is what a build of this branch produces.

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

Every library line publishes as `<upstream version>-ext.<N>`:

- `<upstream version>` is the version JetBrains published the same sources as in Compose
  Multiplatform 1.11.1. Navigation, SavedState and WindowManager here are not the sources
  JetBrains published for 1.11.1 (their `artifactRedirection.version` is newer, or older,
  than what 1.11.1 shipped), so for those three it is the androidx version the sources are
  copied from.
- `N` counts this fork's releases of that upstream version, from 1. A release raises `N` for
  every line in `gradle.properties` in one commit, and the commit is tagged.
- A local build that is not a release passes `-Pextended.publication.dev=true` and publishes
  `-ext.<N>-dev`, so it can never shadow the release of the same number in a local
  repository.
- A version without the suffix is refused when the build is configured.

The versions are the `jetbrains.publication.version.*` properties at the end of
`gradle.properties`. In `androidx.build.Version` the `-ext.<N>` part is kept out of semantic
versioning's pre-release, so that `1.11.1-ext.1` is still a stable version to every check
that asks.

The Compose Gradle plugin of thisisthepy/compose-multiplatform-extended follows the same rule
(`org.thisisthepy.compose:compose-gradle-plugin:1.11.1-ext.1`, plugin ID
`org.thisisthepy.compose`), and its aliases resolve to the coordinates below.

## Every artifact

Each row is a module's root coordinate. Its targets are published beside it as
`<artifact>-<target>` in the same group and version (`ui-desktop`, `ui-macosarm64`,
`ui-linuxx64`, `ui-uikitarm64` and so on), and `desktop` also as
`desktop-jvm-<os>-<arch>` for the six desktop systems. Android variants are not published
by this fork: the root metadata points them at the androidx artifact.

| Line | JetBrains, Compose Multiplatform 1.11.1 | This fork |
|---|---|---|
| `COMPOSE` | `org.jetbrains.compose.annotation-internal:annotation:1.11.1` | `org.thisisthepy.compose.annotation-internal:annotation:1.11.1-ext.1` |
| `COMPOSE` | `org.jetbrains.compose.collection-internal:collection:1.11.1` | `org.thisisthepy.compose.collection-internal:collection:1.11.1-ext.1` |
| `COMPOSE` | `org.jetbrains.compose.animation:animation:1.11.1` | `org.thisisthepy.compose.animation:animation:1.11.1-ext.1` |
| `COMPOSE` | `org.jetbrains.compose.animation:animation-core:1.11.1` | `org.thisisthepy.compose.animation:animation-core:1.11.1-ext.1` |
| `COMPOSE` | `org.jetbrains.compose.animation:animation-graphics:1.11.1` | `org.thisisthepy.compose.animation:animation-graphics:1.11.1-ext.1` |
| `COMPOSE` | `org.jetbrains.compose.foundation:foundation:1.11.1` | `org.thisisthepy.compose.foundation:foundation:1.11.1-ext.1` |
| `COMPOSE` | `org.jetbrains.compose.foundation:foundation-layout:1.11.1` | `org.thisisthepy.compose.foundation:foundation-layout:1.11.1-ext.1` |
| `COMPOSE` | `org.jetbrains.compose.material:material:1.11.1` | `org.thisisthepy.compose.material:material:1.11.1-ext.1` |
| `COMPOSE` | `org.jetbrains.compose.material:material-navigation:1.11.1` | `org.thisisthepy.compose.material:material-navigation:1.11.1-ext.1` |
| `COMPOSE` | `org.jetbrains.compose.material:material-ripple:1.11.1` | `org.thisisthepy.compose.material:material-ripple:1.11.1-ext.1` |
| `COMPOSE` | `org.jetbrains.compose.runtime:runtime:1.11.1` | `org.thisisthepy.compose.runtime:runtime:1.11.1-ext.1` |
| `COMPOSE` | `org.jetbrains.compose.runtime:runtime-saveable:1.11.1` | `org.thisisthepy.compose.runtime:runtime-saveable:1.11.1-ext.1` |
| `COMPOSE` | `org.jetbrains.compose.ui:ui:1.11.1` | `org.thisisthepy.compose.ui:ui:1.11.1-ext.1` |
| `COMPOSE` | `org.jetbrains.compose.ui:ui-geometry:1.11.1` | `org.thisisthepy.compose.ui:ui-geometry:1.11.1-ext.1` |
| `COMPOSE` | `org.jetbrains.compose.ui:ui-backhandler:1.11.1` | `org.thisisthepy.compose.ui:ui-backhandler:1.11.1-ext.1` |
| `COMPOSE` | `org.jetbrains.compose.ui:ui-graphics:1.11.1` | `org.thisisthepy.compose.ui:ui-graphics:1.11.1-ext.1` |
| `COMPOSE` | `org.jetbrains.compose.ui:ui-test:1.11.1` | `org.thisisthepy.compose.ui:ui-test:1.11.1-ext.1` |
| `COMPOSE` | `org.jetbrains.compose.ui:ui-test-junit4:1.11.1` | `org.thisisthepy.compose.ui:ui-test-junit4:1.11.1-ext.1` |
| `COMPOSE` | `org.jetbrains.compose.ui:ui-text:1.11.1` | `org.thisisthepy.compose.ui:ui-text:1.11.1-ext.1` |
| `COMPOSE` | `org.jetbrains.compose.ui:ui-tooling:1.11.1` | `org.thisisthepy.compose.ui:ui-tooling:1.11.1-ext.1` |
| `COMPOSE` | `org.jetbrains.compose.ui:ui-tooling-data:1.11.1` | `org.thisisthepy.compose.ui:ui-tooling-data:1.11.1-ext.1` |
| `COMPOSE` | `org.jetbrains.compose.ui:ui-tooling-preview:1.11.1` | `org.thisisthepy.compose.ui:ui-tooling-preview:1.11.1-ext.1` |
| `COMPOSE` | `org.jetbrains.compose.ui:ui-uikit:1.11.1` | `org.thisisthepy.compose.ui:ui-uikit:1.11.1-ext.1` |
| `COMPOSE` | `org.jetbrains.compose.ui:ui-unit:1.11.1` | `org.thisisthepy.compose.ui:ui-unit:1.11.1-ext.1` |
| `COMPOSE` | `org.jetbrains.compose.ui:ui-util:1.11.1` | `org.thisisthepy.compose.ui:ui-util:1.11.1-ext.1` |
| `COMPOSE` | `org.jetbrains.compose.desktop:desktop:1.11.1` | `org.thisisthepy.compose.desktop:desktop:1.11.1-ext.1` |
| `COMPOSE_MATERIAL3` | `org.jetbrains.compose.material3:material3:1.11.0-alpha07` | `org.thisisthepy.compose.material3:material3:1.11.0-alpha07-ext.1` |
| `COMPOSE_MATERIAL3` | `org.jetbrains.compose.material3:material3-window-size-class:1.11.0-alpha07` | `org.thisisthepy.compose.material3:material3-window-size-class:1.11.0-alpha07-ext.1` |
| `COMPOSE_MATERIAL3` | `org.jetbrains.compose.material3:material3-adaptive-navigation-suite:1.11.0-alpha07` | `org.thisisthepy.compose.material3:material3-adaptive-navigation-suite:1.11.0-alpha07-ext.1` |
| `COMPOSE_MATERIAL3_ADAPTIVE` | `org.jetbrains.compose.material3.adaptive:adaptive:1.3.0-alpha07` | `org.thisisthepy.compose.material3.adaptive:adaptive:1.3.0-alpha07-ext.1` |
| `COMPOSE_MATERIAL3_ADAPTIVE` | `org.jetbrains.compose.material3.adaptive:adaptive-layout:1.3.0-alpha07` | `org.thisisthepy.compose.material3.adaptive:adaptive-layout:1.3.0-alpha07-ext.1` |
| `COMPOSE_MATERIAL3_ADAPTIVE` | `org.jetbrains.compose.material3.adaptive:adaptive-navigation:1.3.0-alpha07` | `org.thisisthepy.compose.material3.adaptive:adaptive-navigation:1.3.0-alpha07-ext.1` |
| `COMPOSE_MATERIAL3_ADAPTIVE` | `org.jetbrains.compose.material3.adaptive:adaptive-navigation3:1.3.0-alpha07` | `org.thisisthepy.compose.material3.adaptive:adaptive-navigation3:1.3.0-alpha07-ext.1` |
| `LIFECYCLE` | `org.jetbrains.androidx.lifecycle:lifecycle-common:2.11.0-beta01` | `org.thisisthepy.compose.androidx.lifecycle:lifecycle-common:2.11.0-beta01-ext.1` |
| `LIFECYCLE` | `org.jetbrains.androidx.lifecycle:lifecycle-runtime:2.11.0-beta01` | `org.thisisthepy.compose.androidx.lifecycle:lifecycle-runtime:2.11.0-beta01-ext.1` |
| `LIFECYCLE` | `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel:2.11.0-beta01` | `org.thisisthepy.compose.androidx.lifecycle:lifecycle-viewmodel:2.11.0-beta01-ext.1` |
| `LIFECYCLE` | `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-savedstate:2.11.0-beta01` | `org.thisisthepy.compose.androidx.lifecycle:lifecycle-viewmodel-savedstate:2.11.0-beta01-ext.1` |
| `LIFECYCLE` | `org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.11.0-beta01` | `org.thisisthepy.compose.androidx.lifecycle:lifecycle-runtime-compose:2.11.0-beta01-ext.1` |
| `LIFECYCLE` | `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0-beta01` | `org.thisisthepy.compose.androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0-beta01-ext.1` |
| `LIFECYCLE` | `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-navigation3:2.11.0-beta01` | `org.thisisthepy.compose.androidx.lifecycle:lifecycle-viewmodel-navigation3:2.11.0-beta01-ext.1` |
| `NAVIGATION` | `org.jetbrains.androidx.navigation:navigation-compose:2.9.2` | `org.thisisthepy.compose.androidx.navigation:navigation-compose:2.10.0-alpha01-ext.1` |
| `NAVIGATION` | `org.jetbrains.androidx.navigation:navigation-common:2.9.2` | `org.thisisthepy.compose.androidx.navigation:navigation-common:2.10.0-alpha01-ext.1` |
| `NAVIGATION` | `org.jetbrains.androidx.navigation:navigation-runtime:2.9.2` | `org.thisisthepy.compose.androidx.navigation:navigation-runtime:2.10.0-alpha01-ext.1` |
| `NAVIGATION_3` | `org.jetbrains.androidx.navigation3:navigation3-ui:1.1.1` | `org.thisisthepy.compose.androidx.navigation3:navigation3-ui:1.1.1-ext.1` |
| `NAVIGATION_EVENT` | `org.jetbrains.androidx.navigationevent:navigationevent-compose:1.1.0` | `org.thisisthepy.compose.androidx.navigationevent:navigationevent-compose:1.1.0-ext.1` |
| `SAVEDSTATE` | `org.jetbrains.androidx.savedstate:savedstate:1.4.0` | `org.thisisthepy.compose.androidx.savedstate:savedstate:1.5.0-alpha01-ext.1` |
| `SAVEDSTATE` | `org.jetbrains.androidx.savedstate:savedstate-compose:1.4.0` | `org.thisisthepy.compose.androidx.savedstate:savedstate-compose:1.5.0-alpha01-ext.1` |
| `WINDOW` | `org.jetbrains.androidx.window:window-core:1.5.1` | `org.thisisthepy.compose.androidx.window:window-core:1.5.0-ext.1` |

## What is not renamed, and why

- **skiko.** `extended/skiko` builds skiko for `mingwX64` and as a static archive from
  JetBrains' sources with a patch, into a local repository, under JetBrains' own
  `org.jetbrains.skiko`. It is not published by this fork, and every Compose module asks for
  skiko by that coordinate.
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
