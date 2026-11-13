# Changes in the fork's own modules (`extended/`)

The upstream changelogs under `compose/` stay upstream's. This one is for the modules this fork
adds: the window modules, the design systems, the skiko scripts and the packaging templates.

## Unreleased

### Window modules (`org.thisisthepy.compose.window`, 0.1.0)

- **Fix.** A double click after a slow frame counted as two single clicks: the scene gave
  Compose each pointer event at the time it read it, not when it happened, and Compose's 300 ms
  `doubleTapTimeoutMillis` instead of the system's interval. `WindowEvent` now carries
  `timeMillis`, the platform's own event time (`NSEvent.timestamp` on macOS, `GetMessageTime`
  on Windows, the X server's time on X11, in both the Kotlin/Native and GraalVM layers), which
  `EventClock` puts on the scene's clock and the scene passes to `sendPointerEvent`; an event
  with no time is timed on arrival as before. `WindowPlatform.doubleClickMillis` (default
  500) is the system's interval (`NSEvent.doubleClickInterval`, `GetDoubleClickTime`, GNOME's
  `double-click` or KDE's `DoubleClickInterval` on Linux) and the scene's
  `ViewConfiguration.doubleTapTimeoutMillis`. The C event record grows by the time, to 136
  bytes. (DarkPyonix/compose-rust Ember editor double click)
- **Fix.** On GNOME, text was scaled twice by any text size other than 100%: the settings
  daemon writes `Xft.dpi` as 96 x the window scaling factor x `text-scaling-factor`, the X11
  windows took their scale from that, and they report `text-scaling-factor` as the text scale
  (`Density.fontScale`) on top. Both X11 windows (Kotlin/Native and GraalVM) now take the
  factor out of `Xft.dpi` where that DPI is a whole multiple of 96 x the factor, so the window
  scale is the desktop's scaling factor and only text grows by the text size, as on macOS and
  Windows. `GDK_SCALE`, desktops without GNOME's factor and DPIs it does not divide read as
  before. The rule is `X11Scale.withoutTextScale` in `common`. (#156, #205,
  DarkPyonix/compose-rust#18)
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
- **Fix.** On Windows, a refused `ResizeBuffers` lost the new size, so the old frame stayed
  stretched over the new client area (`DXGI_SCALING_STRETCH`) until the window was given
  another size. The frame is still skipped, and the size is now asked for again by the next
  frame, which is asked for at once, up to three refusals in a row. (DarkPyonix/compose-rust#18)
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
