# compose-multiplatform-core-extended

Compose Multiplatform libraries with desktop windows that need no AWT, on GraalVM native-image and on Kotlin/Native.

Korean: [README_ko.md](https://github.com/thisisthepy/compose-multiplatform-core-extended/blob/extended/extended/README_ko.md)

## Features

Status is as of 2026-10-17. Items marked in progress or planned are not on `extended` yet.

- Two paths to a desktop application with no AWT: GraalVM native-image and Kotlin/Native. Each one ships an application as one executable, with no Java runtime beside it and nothing unpacked at start.
- AWT-free desktop windows on both paths. macOS works on both: the Kotlin/Native window (AppKit and Metal) and the GraalVM native-image window. Linux (X11) works on both: the GraalVM window and the Kotlin/Native window (Xlib, GLX, XIM and AT-SPI). Windows is in progress: the shared Win32 window, `windows-shared`, is not merged ([#32](https://github.com/thisisthepy/compose-multiplatform-core-extended/pull/32)). Until it is, compose-rust's Kotlin/Native renderer keeps its own Windows window ([DarkPyonix/compose-rust#26](https://github.com/DarkPyonix/compose-rust/issues/26)).
- The window modules, under `extended/window` with the group `org.thisisthepy.compose.window`. The window, its pointer, key and IME input, the text and edit menus, the clipboard, file drops and the window chrome live there, not in any renderer. Their `common` module holds the window state machine, minimum size, event normalisation, frame-request coalescing, IME composition state, dark mode and DPI, and depends on the Kotlin standard library only. Both paths use it. Parity tests that run the same checks against both are planned ([#70](https://github.com/thisisthepy/compose-multiplatform-core-extended/issues/70)). See [Window modules](#window-modules).
- Windows executables are linked by MSVC on both paths. JetBrains builds Skia for Windows with MSVC, so the final link is MSVC's, and there is no MinGW route. The GraalVM path links this way today; the Kotlin/Native link step is open (see [MinGW x64 target](#mingw-x64-target)).
- Design systems and Liquid Glass: Material 3, Cupertino, Fluent, GNOME, Breeze and Deepin themes, and the Liquid Glass material, as standalone libraries in `extended/design-systems`. They depend on published Compose only and never on a renderer, so they can be published on their own.
- skiko without JAWT: a static skiko archive that a native image links, with no dependency on the JDK's `libjawt`.
- AWT-free clipboard and pointer icons, so a window without AWT still copies, pastes and changes its cursor.
- `linuxX64`, `linuxArm64` and `mingwX64` Kotlin/Native source sets for the Compose UI modules are in the tree. The Linux ones are built on CI: the "Extended window linux" workflow publishes them before it builds and tests `native/linux`. No CI job builds the `mingwX64` ones yet, and running a renderer linked from them on Windows is open in [#3](https://github.com/thisisthepy/compose-multiplatform-core-extended/issues/3).
- An upstream public API guard: a workflow compares the public API of the Compose modules with upstream's, so existing Compose code keeps compiling. The fork's own API is in separate modules under `extended/`.

To package an application that uses these, see the Gradle plugin fork,
[compose-multiplatform-extended](https://github.com/thisisthepy/compose-multiplatform-extended/blob/extended/extended/README.md).

## About this fork

This is thisisthepy's fork of
[JetBrains/compose-multiplatform-core](https://github.com/JetBrains/compose-multiplatform-core).
Its work lives on the `extended` branch, on top of JetBrains `release/1.11` at `73ac849`
("Copy Jetpack Compose 1.11.2"). The repository root stays as upstream has it. What the fork
adds is either a platform source set inside a Compose module or a script under `extended/`.

## What it adds

| Addition | Status | Where |
|---|---|---|
| `linuxX64` and `linuxArm64` targets for the Compose UI modules | implemented | module source sets |
| `mingwX64` target for the Compose UI modules, and skiko for it | implemented | module source sets, [`extended/skiko`](https://github.com/thisisthepy/compose-multiplatform-core-extended/blob/extended/extended/skiko/README.md) |
| The native text context menu on macOS (`NSMenu`) | implemented | `foundation` |
| skiko's JVM natives as a static archive, for a GraalVM native image (macOS arm64, Linux x64, Windows x64) | implemented | [`extended/skiko`](https://github.com/thisisthepy/compose-multiplatform-core-extended/blob/extended/extended/skiko/README.md) |
| Skia's ICU data compiled into the Windows static archive | implemented | `extended/skiko/embedded_icu.cpp` |
| skiko-awt with an AWT switch, so a toolkit-free native image keeps no Swing or `UIManager` | implemented | `extended/skiko/build-skiko-awt.sh` |
| The window modules: window, input, IME, menus and chrome for both paths | implemented for macOS and Linux; Windows open in [#32](https://github.com/thisisthepy/compose-multiplatform-core-extended/pull/32) | [`extended/window`](#window-modules) |
| Compose's window procedure for Windows (caption, title bar, icon) | planned | branch [`feature/windows-window-chrome`](https://github.com/thisisthepy/compose-multiplatform-core-extended/tree/feature/windows-window-chrome), [DarkPyonix/compose-rust#26](https://github.com/DarkPyonix/compose-rust/issues/26) |
| `org.thisisthepy.compose` coordinates, versioned `<upstream version>-ext.<N>` | implemented | [`extended/COORDINATES.md`](https://github.com/thisisthepy/compose-multiplatform-core-extended/blob/extended/extended/COORDINATES.md), [Maven coordinates](#maven-coordinates) |
| Six design systems and the Liquid Glass material, as compose-rust had them | imported | [`extended/design-systems`](https://github.com/thisisthepy/compose-multiplatform-core-extended/blob/extended/extended/design-systems/README.md) |
| Design systems as `org.thisisthepy.compose.*` libraries | planned | [DarkPyonix/compose-rust#39](https://github.com/DarkPyonix/compose-rust/issues/39) |

### Linux targets

JetBrains publishes no Kotlin/Native target for Linux. The target is not missing because it
cannot be built: what is missing is the handful of platform declarations every other target
already has. The fork adds them: the key table, the locale, the clipboard (`wl-copy`, `xclip`
or `xsel`, whichever the session has), the URI handler (`xdg-open`), drag and drop, focus, the
pointer icon, tracing, the font resolver and the string delegate. It also gives a snapshot's
apply notifications their own dispatchers, because Linux has no main run loop to send them on.

### MinGW x64 target

Kotlin/Native's only Windows target is MinGW, and neither Compose nor skiko publishes it. Most
of the platform declarations are the Linux ones. Six are Windows' own: the thread identity, the
wheel distance, the pointer icon, the URI handler, the locale and the Win32 clipboard.

skiko comes as two halves for two ABIs. The Kotlin half is built for `mingwX64` from a pinned
skiko (v0.144.6) and a patch. The C++ half is compiled in MSVC mode into a static library,
`skiko-bridges.lib`, because JetBrains builds Skia for Windows with MSVC. The skiko README
explains why there is no skiko fork and how the halves meet:
[extended/skiko/README.md](https://github.com/thisisthepy/compose-multiplatform-core-extended/blob/extended/extended/skiko/README.md).

The final link of a Windows executable is MSVC's, whichever path built it. A GraalVM native image
on Windows is linked by MSVC today. A Kotlin/Native `mingwX64` executable is linked the same way:
Kotlin/Native's MinGW objects are rewritten so the MSVC linker accepts them, and it links them with
Skia and skiko's MSVC half. There is no MinGW route, because a MinGW-only link would need a Skia
built for MinGW, which JetBrains does not make. **Status: open.** The Gradle plugin's MSVC link
step for Kotlin/Native is in
[compose-multiplatform-extended#31](https://github.com/thisisthepy/compose-multiplatform-extended/pull/31).

Running a renderer linked from these on Windows itself is still open in
[#3](https://github.com/thisisthepy/compose-multiplatform-core-extended/issues/3); so far it has
run under Wine.

### Static skiko for a native image

skiko ships its JVM natives as a shared library inside a jar, which the desktop loader unpacks
and opens by path. A single executable cannot carry a second file. So
`build-skiko-static-jvm.sh` archives the same objects that library is linked from, plus the
prebuilt Skia, and the native image links them in. One object changes: skiko's `jawt.o` opens
`<java.home>/lib/libjawt` by path, and a native image has no `java.home`, so `static_jawt.c`
calls the linked-in `JAWT_GetAWT` instead.

On Windows, Skia looks for `icudtl.dat` beside the executable and stops without it.
`embedded_icu.cpp` compiles that data into the archive with `#embed` and hands it to ICU from
memory, so the executable needs no data file.

The Gradle plugin task that consumes this archive is `packageNativeImage` in
[thisisthepy/compose-multiplatform-extended](https://github.com/thisisthepy/compose-multiplatform-extended/blob/extended/extended/README.md).

### Window modules

The desktop window code lives in `extended/window`, a standalone Amper project like
`extended/design-systems`. The window, pointer and key input, IME, the text context and edit
menus, the clipboard, file drops, the window chrome and accessibility are all here. Nothing in it
may depend on a renderer, its wire protocol or its node table (`extended/window/project.yaml`
says so), so any Compose application can open a window through it.

| Module | Artifact | Platforms | What it is |
|---|---|---|---|
| `common` | `org.thisisthepy.compose.window:common` | JVM, `macosArm64`, `linuxX64`, `linuxArm64`, `mingwX64` | `WindowPlatform`, the event, state and policy types, and the rules every platform layer shares. No toolkit and no Compose |
| `scene` | `org.thisisthepy.compose.window:scene` | JVM, `macosArm64` | Hosts a Compose scene in a window opened through `WindowPlatform`: the frame loop, and pointer, key and IME text routed through Compose's platform text input |
| `native/macos` | `org.thisisthepy.compose.window:native-macos` | `macosArm64` | The Kotlin/Native window: AppKit, Metal, the native text and edit menus, the window chrome |
| `native/linux` | `org.thisisthepy.compose.window:native-linux` | `linuxX64`, `linuxArm64` | The Kotlin/Native window: Xlib, GLX, XIM, and AT-SPI over D-Bus |
| `graalvm/graalvm-macos` | `org.thisisthepy.compose.window:graalvm-macos` | JVM (native image) | The AppKit window for a native image: `appkit_window.m` and its Kotlin side, and `appKitWindowSurface`, the Metal surface a `scene` draws into |
| `graalvm/graalvm-linux` | `org.thisisthepy.compose.window:graalvm-linux` | JVM (native image) | The X11 window for a native image: `x11_window.c` and its Kotlin side, and `x11WindowSurface`, the GL surface a `scene` draws into |

Each is at version `0.1.0`. The "Extended window publish" workflow publishes them to the local
Maven repository the way compose-rust consumes them; `native/linux` joins it in
[#111](https://github.com/thisisthepy/compose-multiplatform-core-extended/pull/111). Nothing is on a public repository yet. The Windows window,
`windows-shared`, is open in [#32](https://github.com/thisisthepy/compose-multiplatform-core-extended/pull/32).

#### macOS title bar and accessibility

Both macOS windows, `native/macos` and `graalvm/graalvm-macos`, build their title bar from the
same two `WindowConfig` options, by one rule in `common` (`MacosTitleBar.of`), so a
configuration gives the same corners, buttons and content top on either path.

| `chrome` | `titleBar` | What the window gets |
|---|---|---|
| `WindowChrome.Modern` (default) | `TitleBarStyle.Normal` (default) | Content runs under a transparent title bar. The title is carried for the switcher but not drawn. The system's close, minimise and zoom buttons stay, set in as a unified toolbar sets them. |
| `WindowChrome.Modern` | `TitleBarStyle.Simple` | The same, with the plain title bar's height, button place and corner radius. |
| `WindowChrome.System` | either | The system's own title bar, with its title drawn and the content below it. |

`WindowPlatform.caption()` reports the strip to keep clear of, in points: the bar's height, the
room the buttons take at the leading edge, and the radius of the window's corners.
`buttonsDrawnByScene` is false, because the buttons are the system's. AppKit has no public API
for a window's corner radius, so the radius comes from a table measured in CI on each release
with a runner: macOS 14 and 15 draw 10.25 pt in both styles, and macOS 26 draws 26.75 pt for
`Normal` and 16.75 pt for `Simple`. No private API is used.

`WindowPlatform.setAccessibility(elements)` gives VoiceOver one AppKit accessibility element per
`AccessibleElement`, nested by `parentId`, with its role and subrole, label, value, range, hint
and enabled, focused and selected states. An element keeps its object for as long as its `id`
does. A reader's press, increment, decrement, menu, value change (a field's text or a slider's
value), focus request and scroll arrive at `WindowListener.onAccessibilityAction(id, action,
argument)` on the thread that pumps. `accessibilityActive` turns true once a reader has asked the
window anything or VoiceOver is on, so a scene can skip describing itself while nobody listens.

### Windows window chrome (planned)

The branch `feature/windows-window-chrome` adds Compose's window procedure for Windows to the
static skiko archive. With it, content runs under the caption and draws its own title bar, an
undecorated window keeps its minimum size from every edge, and ownerless windows carry the
application's icon. It is not merged into `extended` yet.

### Design systems

The design systems compose-rust used to hold in its `design-systems/` directory are in
[`extended/design-systems`](https://github.com/thisisthepy/compose-multiplatform-core-extended/blob/extended/extended/design-systems/README.md), unchanged: a standalone Amper project with its own wrapper and
its own workflow, which builds against published Compose rather than the modules in this
repository. The Liquid Glass material is there too, as the `liquid-glass` module. None of it
depends on a renderer, and nothing may be added that would make it do so, because that is what
lets the design systems be published on their own. From there they become libraries, tracked in
[DarkPyonix/compose-rust#39](https://github.com/DarkPyonix/compose-rust/issues/39):

- `org.thisisthepy.compose.designsystem`: the shared contract (the `DesignSystem` interface,
  role enums and tokens).
- Layer 1, one library per system: `org.thisisthepy.compose.material3` (an adapter over
  `androidx.compose.material3`), `.cupertino`, `.fluent`, `.gnome`, `.breeze`, `.deepin` and
  `.liquidglass`. Each has its own `XxxTheme`, color scheme, typography, shapes and components.
- Layer 2, `org.thisisthepy.compose.adaptive`: `AdaptiveTheme` picks the platform's system and
  neutral components delegate to layer 1.

Layer 1 does not depend on `adaptive`, because `adaptive` depends on all of layer 1. This is
not JetBrains' `androidx.compose.material3.adaptive`, which is about window size classes.

## Building

The commands below run from the repository root of a checkout of `extended`, with a JDK 17 in
`JAVA_HOME` unless a section says otherwise. Every build publishes to the local Maven
repository (`~/.m2/repository`).

### Compose modules for Linux, macOS or the desktop JVM

```sh
extended/compose/publish-local.sh linuxX64          # or macosArm64, linuxArm64, mingwX64, desktop
python3 extended/compose/check-local.py             # what it left in ~/.m2 is complete
```

It publishes every module an application draws with (foundation, Material 3 and what they
reach) for the targets named, at the versions in `gradle.properties`, and `--list` prints the
Gradle tasks without running them. Each module gets two publications: the target's own, which
carries the klib or jar, and the root one, which says which targets exist. Without the root, a
consumer is told the module does not support the platform. Material 2's navigation, the
adaptive family and the navigation suite are not on the list, because each depends on a
published artifact with no Linux variant.

The runtime, `runtime-saveable` and the internal annotation and collection libraries are on
the list although they carry no classes. They are compatibility stubs that point at the
androidx artifacts, as JetBrains' are, and the modules above name them at this fork's version.

The CI jobs that build the window modules run this through `.github/actions/fork-compose`,
which caches the result until the Compose sources change.
[compose-rust's `build-compose.sh`](https://github.com/DarkPyonix/compose-rust/blob/develop/renderer/scripts/build-compose.sh)
runs it against a pinned commit of the fork.

### mingwX64

First skiko, then the Compose modules. The Compose build reads `org.jetbrains.skiko` from the
local Maven repository before Maven Central (`buildSrc/repos.gradle`), because the root
metadata found first wins and only the local one names `mingw_x64`.

```sh
extended/skiko/build-skiko-mingw.sh <work-dir>          # add --clean to start over
```

It publishes `org.jetbrains.skiko:skiko-mingwx64:0.144.6` and writes the C++ half to
`<work-dir>/out/windows-x64/` (`skiko-bridges.lib`, `skia/`, `skia-include/`). It needs git,
curl, unzip, python3, a JDK 17 or 21, Kotlin/Native's LLVM in `~/.konan`, and the MSVC runtime
and Windows SDK as cargo-xwin lays them out (`cargo xwin build --target x86_64-pc-windows-msvc`
once, or set `XWIN_DIR`).

Then publish each module as above with `publishMingwX64PublicationToMavenLocal` in place of
the Linux task.

### Static skiko for a native image

```sh
extended/skiko/build-skiko-static-jvm.sh <work-dir>
```

It builds for the host it runs on: macOS arm64, Linux x64 or Windows x64. The output is in
`<work-dir>/out/<os>-<arch>/`: `libskiko-static.a` (`skiko-static.lib` on Windows) and
`skia/`. The skiko README says how each must be linked.

- All hosts: git and a JDK 17 or 21 in `JAVA_HOME`.
- Linux x64: g++, ar, and the X11, GL, fontconfig and dbus development headers.
- Windows x64: Git Bash with the MSVC tools on PATH (a Developer prompt), and `clang-cl` on
  PATH, because skiko compiles its Windows bindings with it (`winget install LLVM.LLVM`). The
  script stops early if `clang-cl` is missing.

## Maven coordinates

Everything this fork builds is published under `org.thisisthepy.compose`, never under a
JetBrains group, because an artifact under `org.jetbrains.compose` that JetBrains did not build
cannot be told from the real one, and whichever has the higher version silently replaces the
other. Artifact names, packages, classes and signatures are JetBrains'; only the group and the
version differ. [`COORDINATES.md`](COORDINATES.md) lists every coordinate.

| What | Coordinate |
|---|---|
| Compose modules | `org.thisisthepy.compose.<group>:<artifact>:1.11.1-ext.1`, for example `org.thisisthepy.compose.ui:ui:1.11.1-ext.1` and its `ui-desktop`, `ui-linuxx64`, `ui-mingwx64`, `ui-macosarm64` targets |
| Material 3 | `org.thisisthepy.compose.material3:material3:1.11.0-alpha07-ext.1` |
| Libraries JetBrains publishes as `org.jetbrains.androidx.*` | `org.thisisthepy.compose.androidx.<library>`, for example `org.thisisthepy.compose.androidx.lifecycle:lifecycle-runtime-compose:2.11.0-beta01-ext.1` |
| Runtime, `runtime-saveable`, annotation and collection | `org.thisisthepy.compose.runtime:runtime:1.11.1-ext.1` and the others: stubs with no classes that point at `androidx.compose.runtime`, as JetBrains' do |
| The window modules | `org.thisisthepy.compose.window:<module>:0.1.0` |
| skiko for `mingwX64` | `org.jetbrains.skiko:skiko-mingwx64:0.144.6`, with skiko's root metadata extended to name it |
| skiko-awt with the AWT switch | `org.jetbrains.skiko:skiko-awt:0.144.6.1`, from `extended/skiko/build-skiko-awt.sh` |

Versions are `<upstream version>-ext.<N>`: the version JetBrains published the same sources as,
and the count of this fork's releases of it. A local build that is not a release passes
`-Pextended.publication.dev=true` and publishes `-ext.<N>-dev`. A version without the suffix
is refused.

skiko keeps JetBrains' group, because every Compose module asks for it there. The patched
skiko-awt therefore gets a version of its own, `0.144.6.1`, which sorts above JetBrains'
`0.144.6` in Gradle and in Maven, so a consumer that names it gets it even when Compose brings
in `0.144.6`; its natives are JetBrains' runtime jars at `0.144.6`.

Nothing is on a public repository yet. A consumer puts `mavenLocal()` first:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        google()
    }
}
```

```kotlin
// build.gradle.kts, in a Kotlin Multiplatform project
kotlin {
    linuxX64()
    sourceSets.commonMain.dependencies {
        implementation("org.thisisthepy.compose.foundation:foundation:1.11.1-ext.1")
        implementation("org.thisisthepy.compose.material3:material3:1.11.0-alpha07-ext.1")
    }
}
```

The Gradle plugin of
[compose-multiplatform-extended](https://github.com/thisisthepy/compose-multiplatform-extended/blob/extended/extended/README.md)
resolves its aliases (`compose.foundation`, `compose.desktop.currentOs` and the rest) to these
coordinates.

What it costs: a third-party Compose library is built against `org.jetbrains.compose`. Next to
this fork it brings JetBrains' Compose UI in a second time, which no resolver can merge because
the groups differ. Anything used with this fork has to be built against it.
