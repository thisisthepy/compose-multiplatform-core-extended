# compose-multiplatform-core-extended

This is thisisthepy's fork of
[JetBrains/compose-multiplatform-core](https://github.com/JetBrains/compose-multiplatform-core).
Its work lives on the `extended` branch, on top of JetBrains `release/1.11` at `73ac849`
("Copy Jetpack Compose 1.11.2"). The repository root stays as upstream has it. What the fork
adds is either a platform source set inside a Compose module or a script under `extended/`.

Korean: [README_ko.md](https://github.com/thisisthepy/compose-multiplatform-core-extended/blob/extended/extended/README_ko.md)

## What it adds

| Addition | Status | Where |
|---|---|---|
| `linuxX64` and `linuxArm64` targets for the Compose UI modules | implemented | module source sets |
| `mingwX64` target for the Compose UI modules, and skiko for it | implemented | module source sets, [`extended/skiko`](https://github.com/thisisthepy/compose-multiplatform-core-extended/blob/extended/extended/skiko/README.md) |
| The native text context menu on macOS (`NSMenu`) | implemented | `foundation` |
| skiko's JVM natives as a static archive, for a GraalVM native image (macOS arm64, Linux x64, Windows x64) | implemented | [`extended/skiko`](https://github.com/thisisthepy/compose-multiplatform-core-extended/blob/extended/extended/skiko/README.md) |
| Skia's ICU data compiled into the Windows static archive | implemented | `extended/skiko/embedded_icu.cpp` |
| Compose's window procedure for Windows (caption, title bar, icon) | planned | branch [`feature/windows-window-chrome`](https://github.com/thisisthepy/compose-multiplatform-core-extended/tree/feature/windows-window-chrome), [DarkPyonix/compose-rust#26](https://github.com/DarkPyonix/compose-rust/issues/26) |
| `org.thisisthepy.compose` coordinates | planned | branch [`chore/thisisthepy-coordinates`](https://github.com/thisisthepy/compose-multiplatform-core-extended/tree/chore/thisisthepy-coordinates) |
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

### Windows window chrome (planned)

The branch `feature/windows-window-chrome` adds Compose's window procedure for Windows to the
static skiko archive. With it, content runs under the caption and draws its own title bar, an
undecorated window keeps its minimum size from every edge, and ownerless windows carry the
application's icon. It is not merged into `extended` yet.

### Design systems

The design systems compose-rust used to hold in its `design-systems/` directory are in
[`extended/design-systems`](https://github.com/thisisthepy/compose-multiplatform-core-extended/blob/extended/extended/design-systems/README.md), unchanged: a standalone Amper project with its own wrapper and
its own workflow, which builds against published Compose rather than the modules in this
repository. From there they become libraries, tracked in
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

### Compose modules for Linux or macOS

Each module needs two publications: the target's own, which carries the klib, and the root
one, which says which targets exist. Without the root, a consumer is told the module does not
support the platform.

```sh
./gradlew --no-daemon --no-configuration-cache \
    -Pjetbrains.publication.version.COMPOSE=1.11.1 \
    -Pjetbrains.publication.version.COMPOSE_MATERIAL3=1.11.0-alpha07 \
    :compose:ui:ui:publishLinuxX64PublicationToMavenLocal \
    :compose:ui:ui:publishKotlinMultiplatformPublicationToMavenLocal
```

Repeat the two tasks for every module the application draws with. For `linuxX64` that is
`compose:animation:animation`, `animation-core`, `compose:foundation:foundation`,
`foundation-layout`, `compose:material:material-ripple`, `compose:material3:material3`,
`compose:ui:ui`, `ui-backhandler`, `ui-geometry`, `ui-graphics`, `ui-text`,
`ui-tooling-preview`, `ui-unit` and `ui-util`. For `macosArm64`, use
`publishMacosArm64PublicationToMavenLocal`; only `compose:foundation:foundation` and
`compose:ui:ui` differ from JetBrains' build there. Material 2's navigation, the adaptive family
and the navigation suite cannot be built for Linux, because each depends on a published
artifact with no Linux variant.

[compose-rust's `build-compose.sh`](https://github.com/DarkPyonix/compose-rust/blob/develop/renderer/scripts/build-compose.sh)
runs exactly this against a pinned commit of the fork.

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

Today the fork publishes under JetBrains' own groups and the version it stands in for, so a
build that reads the local repository first takes the patched modules and resolves the rest
from JetBrains:

| What | Coordinate |
|---|---|
| Compose modules | `org.jetbrains.compose.<group>:<artifact>:1.11.1`, for example `org.jetbrains.compose.ui:ui:1.11.1` and its `ui-linuxx64`, `ui-mingwx64`, `ui-macosarm64` targets |
| Material 3 (with the property above) | `org.jetbrains.compose.material3:material3:1.11.0-alpha07` |
| skiko for `mingwX64` | `org.jetbrains.skiko:skiko-mingwx64:0.144.6`, with skiko's root metadata extended to name it |

`jetbrains.publication.version.COMPOSE=1.11.1` is set in `gradle.properties`. Nothing is on a
public repository yet. A consumer puts `mavenLocal()` first:

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
        implementation("org.jetbrains.compose.foundation:foundation:1.11.1")
        implementation("org.jetbrains.compose.material3:material3:1.11.0-alpha07")
    }
}
```

**Status: planned.** The branch
[`chore/thisisthepy-coordinates`](https://github.com/thisisthepy/compose-multiplatform-core-extended/tree/chore/thisisthepy-coordinates)
moves everything to `org.thisisthepy.compose`, because an artifact under `org.jetbrains.compose`
that JetBrains did not build cannot be told from the real one. Groups become
`org.thisisthepy.compose.<group>` (`org.thisisthepy.compose.androidx.<library>` for the
`org.jetbrains.androidx` ones), and versions become `<upstream version>-ext.<N>`, for example
`org.thisisthepy.compose.ui:ui:1.11.1-ext.1`. skiko keeps `org.jetbrains.skiko`. That branch's
[`extended/COORDINATES.md`](https://github.com/thisisthepy/compose-multiplatform-core-extended/blob/chore/thisisthepy-coordinates/extended/COORDINATES.md)
lists every coordinate.
