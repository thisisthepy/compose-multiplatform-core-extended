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
