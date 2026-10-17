# skiko for Kotlin/Native on Windows

skiko publishes no Kotlin/Native target for Windows, so Compose built for `mingwX64` in
this fork has no skiko to depend on. This directory supplies one, as a patch against a
pinned skiko and a script that builds it, rather than as a fork of skiko: a mingwX64 skiko
will not need publishing for a long time, and a fork is a repository to keep alive.

| File | What it is |
|---|---|
| `0001-mingw-x64-target.patch` | A `mingwX64` target that builds skiko's Kotlin half only |
| `extend-skiko-root.py` | Adds the mingw_x64 variants to skiko's root module metadata |
| `republish-skiko-mingw.py` | Writes it as `org.thisisthepy.compose.skiko:skiko-mingwx64` with the C++ half as its `msvc` zip, and points Compose's mingwX64 modules at it |
| `build-skiko-mingw.sh` | Applies the patch, publishes to the local Maven repository, compiles the C++ half |
| `build-skiko-static-jvm.sh` | skiko's JVM natives as a static archive, for a native image that links Skia in |
| `static_jawt.c` | Replaces skiko's `jawt.o` in that archive: calls the linked-in `JAWT_GetAWT` instead of opening `<java.home>/lib/libjawt` by path |

## The pin

    https://github.com/JetBrains/skiko.git
    9a5b398bb2044fff7e7a84fbfd6f4b803e4427c0   (v0.144.6)

The version Compose 1.11 asks for. Skia is the one that revision names, `m144-22f58c9fd4`,
from JetBrains' own Skia builds.

## Why two halves and two ABIs

Kotlin/Native's only Windows target is MinGW. JetBrains builds Skia for Windows with MSVC,
and skiko's C++ bridges call Skia's C++ classes, so they have to be MSVC too. The two halves
meet only in C calls (`org_jetbrains_skia_*`), which both ABIs agree on.

JetBrains' skiko klibs embed their C++ half and Skia, and Kotlin/Native's linker takes them
from there. Here that linker is MinGW's and the C++ half is MSVC's, so the klib carries Kotlin
only and the C++ half ships beside it as the `msvc` zip. A program is linked into one MSVC
executable by `org.thisisthepy.kotlin.native.desktop` (thisisthepy/compose-multiplatform-extended),
which adds that zip, and which deals with the two MinGW conventions that link meets: static
constructors in `.ctors` that the MSVC runtime never runs, and per-function unwind data that
an MSVC-mode linker discards. `.github/workflows/extended-publish-local.yml` links and runs a
small Compose program that way from the published repository alone.

## Build order

1. `build-skiko-mingw.sh <work-dir>`
2. This fork's Compose modules for mingwX64, which read skiko from the local Maven
   repository before Maven Central (`buildSrc/repos.gradle`).
3. `republish-skiko-mingw.py <repository> 0.144.6 <version> <work-dir>/out/windows-x64`.

## skiko linked into a native image (JVM, macOS arm64)

`build-skiko-static-jvm.sh` archives the same objects skiko's JVM shared library is linked
from, so a GraalVM native image can link Skia in rather than load it from a file. It gives
two things to link, and they have to be linked differently:

- `libskiko-static.a`, skiko's own bindings, with `-force_load`: a JNI entry point is reached
  by name, so ordinary archive semantics would drop all of them.
- `skia/*.a`, as ordinary archives: JetBrains' module archives (skottie, sksg, svg) each
  carry their own copy of Skia's core objects, and forcing them in defines those twice.

## skiko with no AWT (embedder path)

An embedder that opens its own window never asks skiko for an AWT drawing surface, so
nothing in the process should load `libjawt` or `libawt`. skiko's JVM library already only
opens `libjawt` when `getAWT()` is called, and its loader (`Library.load`, `Setup.init`)
touches no `java.awt` class unless `skiko.rendering.laf.global` is set. The static archive
was the exception: `static_jawt.c` calls `JAWT_GetAWT` directly, which links JAWT.

| File | What it is |
|---|---|
| `static_no_jawt.c` | `Skiko_GetAWT` that answers "no AWT" and refers to nothing in JAWT. Used by `build-skiko-static-jvm.sh --no-jawt` |
| `tests/LoaderCheck.java` | Loads skiko as an embedder does and fails if `libjawt`/`libawt` is mapped or a `java.awt`, `javax.swing` or `sun.awt` class is loaded |

`.github/workflows/extended-skiko-awt-free.yml` runs the loader check against skiko's
published JVM library, and builds the static archive with `--no-jawt` on Linux and macOS,
where the script fails if the archive still has an undefined `JAWT_` symbol.
