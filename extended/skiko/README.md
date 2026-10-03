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
| The same caption strip, with no band and no buttons drawn: content runs to the top of the window and is told the strip's height as the caption bar and system bar insets, for an application or framework that draws its own title bar and buttons | on with `-Dcompose.windows.caption=content` |
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

## Checks on a real display (Windows)

CI builds hello and runs it once on a runner, which has no GPU and no person looking. These
are the checks only a Windows machine with a display can make. Each one records a screenshot
under `.scratch/window-chrome/<scale>/` on that machine and its numbers in the report.

**Build.** On the Windows machine, from compose-multiplatform-extended at
`feature/hello-dark-check` (hello with `HELLO_DARK`), with this branch's
`build-skiko-static-jvm.sh` output as `SKIKO_STATIC` and this branch's ui-desktop published to
the local Maven repository as 1.11.1 (the `ui-desktop-maven` artifact of the
"Windows window chrome" workflow, unpacked into
`%USERPROFILE%\.m2\repository\org\jetbrains\compose\ui\ui-desktop\`), run
`gradlew packageNativeImage` in `extended/native-image/hello`. Start the executable as
`native-image-hello.exe -Dcompose.windows.chrome.debug=true` from a console and keep its
standard error: the first line must read `installed=true caption=true liveResize=true`.

Do every check below twice: with Settings > Display > Scale at **100%**, then at **200%**
(sign out and in after changing it). Record the scale and the `dpiAwareness=` value from the
debug line with each screenshot.

1. **Sharp, not stretched.** Screenshot the window at 100% of its size (Win+Shift+S, window
   mode). Text and the button glyphs have single-pixel edges at 200%; a blurred, twice-wide
   edge means the process is not per-monitor aware.
2. **Light band.** Run without `HELLO_DARK`. The strip across the top, where the system title
   bar was, is the content's own surface colour, with no seam between strip and content.
   Sample a pixel 8 px below the window's top edge and one 100 px below it, in the same
   column away from the text: they must be equal. The three buttons sit at the right edge,
   46 x 32 (92 x 64 at 200%), glyphs dark on light. Screenshot `light.png`.
3. **Dark band.** Run with `set HELLO_DARK=1`. Same two samples, equal and dark; glyphs light
   on dark. Screenshot `dark.png`. No system title bar colour appears anywhere in either.
4. **Buttons and caption behaviour.** Hover each button: minimise and maximise get a faint
   fill, close turns red (#C42B1C) with a white glyph. Click minimise, restore from the
   taskbar; click maximise (glyph becomes restore, the band stays fully on screen, its top
   is the monitor's top), click again to restore. Double click the empty band: maximises.
   Right click it: the system menu. Drag it: the window moves, and dragging to the screen's
   top edge snaps. Hover the maximise button: there is no Snap Layouts flyout (known, see
   "what is not done"). Close quits the application. Narrator (Win+Ctrl+Enter) reads the
   three as "Minimize button", "Maximize button" ("Restore" when maximised), "Close button".
5. **Frame kept.** The window has the system drop shadow and rounded corners (Windows 11), and
   resizes from all four edges and four corners, including the top edge, where the cursor
   turns into the vertical resize arrow within the top few pixels.
6. **Live resize lag.** The measurement on darkpyonix/compose-rust#26: drag the
   bottom-right corner with `SendInput` (55 steps of 16 px, one step per 16 ms, button held),
   and after each step capture the window with `PrintWindow`/`BitBlt` of the screen area.
   For each capture, the lag is the distance between the window's client rectangle edge
   (`GetClientRect` mapped to the screen) and the edge of what was drawn (the last column and
   row whose pixels are hello's surface colour rather than the stale/black area). Record min,
   max and mean in width and height, and how many steps had no lag. Run it with
   `.scratch/drag-resize-probe.ps1` from #26 on that machine, three times:
   - this build: expected lag 0 on most steps, never more than one step width;
   - this build with `-Dcompose.windows.liveResize=false`: should reproduce #26's numbers
     (max about 30 px, no step without lag), which shows the difference is this change;
   - optionally hello from `extended` before this change, as #26 measured it.
   Keep both CSVs and a few frame captures per run.
7. **Opt-outs.** `-Dcompose.windows.caption=system` gives back the ordinary system title bar
   with nothing drawn by Compose in it. `-Dcompose.windows.caption=content` takes the strip
   but draws no band and no buttons: hello's content starts at the window's top.

Report: the debug line, the scale, the screenshots, the three lag summaries, and every step
above that did not hold, with what was seen.
