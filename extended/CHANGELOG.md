# Changes in the fork's own modules (`extended/`)

The upstream changelogs under `compose/` stay upstream's. This one is for the modules this fork
adds: the window modules, the design systems, the skiko scripts and the packaging templates.

## Unreleased

### Window modules (`org.thisisthepy.compose.window`, 0.1.0)

- **Fix.** The Kotlin/Native X11 window drew at a scale of 1 whatever the display: it now
  measures at `Xft.dpi` / 96 from the root window's `RESOURCE_MANAGER` (or `GDK_SCALE`, as the
  GraalVM window reads it), opens at its size in points times that, takes and reports positions
  and sizes in points, and follows a change of the resources with `onScaleChanged` and a frame.
  The parsing is `X11Scale` in `common`. (DarkPyonix/compose-rust#18)
- **Fix.** The GraalVM X11 window read `Xft.dpi` once, from Xlib's copy of the resources taken
  when the connection opened: it now asks the root window for them and reads them again when
  they change, so the scale it measures (and reports through `onScaleChanged`) follows the
  desktop's setting. (DarkPyonix/compose-rust#18)
- **Fix.** The Windows mouse wheel scrolled in reverse on both the GraalVM and Kotlin/Native
  paths: `WM_MOUSEWHEEL` now negates its delta, because Win32 counts a turn away from the reader
  as positive and Compose reads a positive `scrollDelta.y` as a scroll down. (#169)
- **Breaking default.** `WindowConfig` gains `chrome` and `titleBar`, defaulting to
  `WindowChrome.Modern` and `TitleBarStyle.Normal`: the content runs to the top of the window.
  On Linux that means no system title bar; the scene draws the caption and its buttons. Pass
  `chrome = WindowChrome.System` to keep the platform's title bar. (#144, #146)
- `WindowPlatform` gains `caption()`, `setAccessibility(elements)`, `accessibilityActive`,
  `startMove()`, `startResize(edge)` and `performCaptionAction(action)`, each with a default; an
  implementation that already had a `setAccessibility(List<AccessibleElement>)` now marks it
  `override`. (#144)
- `AccessibleElement` gains `id`, `parentId`, `value`, `hint`, `states`, `actions` and a range,
  and `WindowListener.onAccessibilityAction(id, action, argument)` carries a reader's request
  back. (#144)
- `scene`: `LocalWindowCaption`, `Modifier.windowCaptionPadding()`, the caption buttons it draws
  where the platform has none, and the semantics it hands to the platform while a reader listens.
  `ComposeWindowHost` and `runComposeWindow` take `padContentForCaption` before `content`; a call
  that passed `content` fourth by position names it. The module gains `linuxX64` and
  `linuxArm64`. (#146)
- `scene`: Compose's main-thread work runs on a dispatcher every host shares, so it keeps running
  while any window is open. (#142, #143)
- `graalvm-windows`: the Win32 window for a native image (`Win32GraalWindow`, `Win32Frames`, and the
  upcall a resize draws through), moved from compose-rust. `frameTimeNanos()` reads a
  `FrameClock` on the monotonic clock, so a frame is timed by the wall clock and not by a count
  of 16 ms steps.
- `graalvm-macos` and `graalvm-linux`: `appKitWindowSurface` and `x11WindowSurface`, the surfaces
  `runComposeWindow` draws into. (#138)
- `native/linux` and `graalvm-linux`: the chrome API. Under `WindowChrome.Modern` (the default)
  the window has no window manager frame, `decorated = true` included; `caption()` reports the
  scene's strip (46 points for `Normal`, 32 for `Simple`); `startMove`, `startResize` and
  `performCaptionAction` go through `_NET_WM_MOVERESIZE` and `_NET_WM_STATE`, and a double click
  in the caption maximises or restores. `chrome = WindowChrome.System` keeps the window
  manager's frame. (#149)
- `native/linux` and `graalvm-linux`: AT-SPI is published from the scene's `AccessibleElement`
  records. `AtspiSemanticsSource`, `NodeFacts`, `describeFacts` and graalvm-linux's
  `X11Accessibility` are removed, and `AtspiActions` is `perform(id, action, argument)` and
  `windowOrigin()`. (#149)
- `native/linux-scene` (new, `org.thisisthepy.compose.window:native-linux-scene`): `x11WindowSurface`,
  where `ComposeWindowHost` draws on the Kotlin/Native X11 window, through the window's new
  `makeCurrent()` and `resizePainter`. (#149)
