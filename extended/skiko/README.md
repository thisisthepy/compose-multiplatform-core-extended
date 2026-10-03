# skiko for Kotlin/Native on Windows

skiko publishes no Kotlin/Native target for Windows, so Compose built for `mingwX64` in
this fork has no skiko to depend on. This directory supplies one, as a patch against a
pinned skiko and a script that builds it, rather than as a fork of skiko: a mingwX64 skiko
will not need publishing for a long time, and a fork is a repository to keep alive.

| File | What it is |
|---|---|
| `0001-mingw-x64-target.patch` | A `mingwX64` target that builds skiko's Kotlin half only |
| `extend-skiko-root.py` | Adds the mingw_x64 variants to skiko's root module metadata |
| `build-skiko-mingw.sh` | Applies the patch, publishes to the local Maven repository, compiles the C++ half |
| `build-skiko-static-jvm.sh` | skiko's JVM natives as a static archive, for a native image that links Skia in |
| `static_jawt.c` | Replaces skiko's `jawt.o` in that archive: calls the linked-in `JAWT_GetAWT` instead of opening `<java.home>/lib/libjawt` by path |
| `windows-chrome/` | What a Compose window on Windows needs from its window procedure: the caption band, the live resize held for its frame, the per-monitor DPI declaration, the executable's icon. Compiled with skiko's Windows sources by `build-skiko-static-jvm.sh` |
| `0002-compose-window-chrome-present-hook.patch` | Makes skiko's Direct3D swap report each presented frame to `windows-chrome/` |
| `tests/compose-window-chrome.test.sh` | Checks the decisions in `windows-chrome/compose_window_chrome.h` with any C compiler, and that the native method names agree with Compose's declarations |

## The pin

    https://github.com/JetBrains/skiko.git
    9a5b398bb2044fff7e7a84fbfd6f4b803e4427c0   (v0.144.6)

The version Compose 1.11 asks for. Skia is the one that revision names, `m144-22f58c9fd4`,
from JetBrains' own Skia builds.

## Why two halves and two ABIs

Kotlin/Native's only Windows target is MinGW. JetBrains builds Skia for Windows with MSVC,
and skiko's C++ bridges call Skia's C++ classes, so they have to be MSVC too. The two halves
meet only in C calls (`org_jetbrains_skia_*`), which both ABIs agree on.

An application linking all of it into one MSVC executable has two more MinGW conventions to
deal with: static constructors in `.ctors` that the MSVC runtime never runs, and
per-function unwind data that an MSVC-mode linker discards. That belongs to whoever links
the executable, not to skiko, and is not handled here.

## Build order

1. `build-skiko-mingw.sh <work-dir>`
2. This fork's Compose modules for mingwX64, which read skiko from the local Maven
   repository before Maven Central (`buildSrc/repos.gradle`).

## skiko linked into a native image (JVM, macOS arm64)

`build-skiko-static-jvm.sh` archives the same objects skiko's JVM shared library is linked
from, so a GraalVM native image can link Skia in rather than load it from a file. It gives
two things to link, and they have to be linked differently:

- `libskiko-static.a`, skiko's own bindings, with `-force_load`: a JNI entry point is reached
  by name, so ordinary archive semantics would drop all of them.
- `skia/*.a`, as ordinary archives: JetBrains' module archives (skottie, sksg, svg) each
  carry their own copy of Skia's core objects, and forcing them in defines those twice.

## A Compose window on Windows (`windows-chrome/`)

`ComposeWindow` (and so `Window {}`) on Windows does three things AWT does not, by default and
with no new API. Each has a system property that turns it off.

| Behaviour | Off with |
|---|---|
| The caption strip becomes a band Compose draws, in the colour of the content's top edge, with its own minimise, maximise and close buttons. The frame stays whole: shadow, resize border and Snap are the system's. Content is laid out below the band | `-Dcompose.windows.caption=system` |
| Each step of a live resize waits, at most 50 ms, until a frame at the new size has been presented, so the content does not trail the window's edge | `-Dcompose.windows.liveResize=false` |
| In a native image, a window with no icon wears the executable's icon instead of the toolkit's | `-Dcompose.windows.executableIcon=false` |

And one that has no property: an executable that links this archive declares itself aware of
per-monitor scaling while the C runtime starts, before any window exists and before Java does.
A system property cannot reach that far, so an application that wants another awareness
declares it in its manifest, which wins.

Compose's side is `androidx.compose.ui.window.WindowsWindowChrome` in `compose/ui/ui`. It
reaches these natives through `org.jetbrains.skiko.compose.WindowsWindowChrome`, in skiko's
package because a native image links skiko's packages' JNI methods statically from this
archive. skiko's library from Maven Central does not have them; a JVM run with it keeps the
system caption and AWT's resize, and says so with `-Dcompose.windows.chrome.debug=true`.
