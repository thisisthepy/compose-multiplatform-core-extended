# Changes in the fork's own modules (`extended/`)

The upstream changelogs under `compose/` stay upstream's. This one is for the modules this fork
adds: the window modules, the design systems, the skiko scripts and the packaging templates.

## Unreleased

### Window modules (`org.thisisthepy.compose.window`)

- **Fix.** The Windows window (`windows-shared`) opens on the GDI route by default again;
  `DXC_DCOMP=1` asks for the DirectComposition route. On a real GPU, with a real application
  dragged by hand, the DirectComposition route left a see-through strip inside the border on
  a fast drag that grows the window, and the picture slid on a drag of the left or top edge;
  the GDI route showed neither. The probe saw neither (it drags the right edge, and takes a
  see-through margin for a smaller window). Before this, the route was DirectComposition
  unless `DXC_DCOMP=0`. (#223)

- **Fix.** The Windows window (`windows-shared`) shows its frames through DirectComposition.
  On restore, maximise and snap it showed the new size with the old picture now and then: the
  rectangle and the picture reached DWM in separate steps (#223). The window now has no GDI
  surface (`WS_EX_NOREDIRECTIONBITMAP`); its frames are written into two virtual surfaces that
  take turns, with a Direct3D 11 device of their own, and each frame, its size and its clip
  reach DWM in one commit. A frame that shrinks is committed and taken by DWM before the window
  shrinks; one that grows is committed after the window grew; a snap draws one more frame, of
  the size both rectangles hold. In CI (WARP), faulty frames in 247 size changes went from 11
  and 81 (windows-2022, windows-2025) to 0 and 4, drags stayed at 0, and the window holds about
  2 MB less at rest. A drag trails the pointer by about one composition more than before on
  WARP (16 px median against 0); a real GPU is still to be measured, with
  `c/test/run-lag-probe.ps1`. `DXC_DCOMP=0` keeps the previous route, which is also the
  fallback where DirectComposition is missing. Applications that compile the window now also
  compile `c/win32_dcomp.cpp` (`cl /c /std:c++17 /GR- /EHs-c-`); `dcomp.dll` is loaded at run
  time. (#223, #245)

- **Performance.** On the Kotlin/Native macOS window, a native view that moves without
  changing size (a scroll) is now moved with `setFrameOrigin:` rather than `setFrame:`, and one
  that only changes size with `setFrameSize:`. Four views moved with their size kept cost 69 to
  71 µs a frame together with the Core Animation commit after it through `setFrame:`, and 51 to
  61 µs through `setFrameOrigin:` (medians of 400 frames). Where each view ends is unchanged.
  (DarkPyonix/compose-rust#855)

- **Feature.** The Windows window (`windows-shared`) places native views: `nativeViews` on
  `Win32NativeWindow` and `Win32WindowPlatform` (`WindowsNativeViews`, over the new C calls
  `dxc_native_view_*`). An HWND the application made against the window is put in a child
  window of the window's own above the frame and above every view attached before it, cut to
  its clip, with rounded holes cut out of it by a window region, given its place with the next
  frame after it is presented, and taken out (hidden, never destroyed) when it is detached or
  the window closes. Focus moving into, out of and between views, and the window's activation,
  arrive as `NATIVE_VIEW_FOCUS_IN` and `_OUT`; the pointer leaving the scene for a view and
  coming back as `POINTER_EXIT` and `_ENTER`; over a view the cursor is the view's. On the GDI
  route the swapchain's child window clips its siblings once a view is attached, and the copies
  into the window during a resize leave the views out. On the DirectComposition route the
  visual is now made below the window's child windows (`CreateTargetForHwnd` with topmost
  false), which changes nothing for a window without one, and a view's container is a layered
  child window. (DarkPyonix/compose-rust#932)

- **Fix.** The Windows window (`windows-shared`, so the Kotlin/Native and the GraalVM windows
  both) did not keep its size in points when its DPI changed. It applied the rectangle
  `WM_DPICHANGED` suggests, which Windows scales from the old outer rectangle without knowing
  this window's frame, so a window of 320x240 points, 640x480 pixels at 200 %, came out
  398x297 pixels at 125 % instead of 400x300. The window now remembers its size (in points
  where the application gave it, in pixels at their DPI where the user dragged it,
  `win32_dpi.h`) and gives the client area that size at the new DPI, a half pixel rounded up as
  on X11, at the top left Windows suggests and never under the minimum size. It answers
  `WM_GETDPISCALEDSIZE` with the same size, so the rectangle Windows suggests is already right,
  and measures the client area afterwards and corrects the outer size once should the frame
  differ. Changes back and forth come back to the same size with no drift. A maximised,
  minimised or snapped window keeps the place it fills and gets its size back when it is
  restored. (DarkPyonix/compose-rust FR-82)

### Publishing

- Releases on Maven Central leave out tvOS, watchOS and Kotlin/JS (`CENTRAL_SKIP_TARGETS`; a
  dispatch with `all_targets` publishes them) and write only the `.md5` and `.sha1` checksums
  Central requires. On 1.11.1-ext.5's bundle that is 9,356 files instead of 17,844. Wasm, iOS,
  macOS, Linux, Windows, the desktop JVM and Android stay.
- No branch push, pull request or schedule starts a workflow that uploads:
  `extended/publish/check_publish_triggers.py` fails the publish plan otherwise. The dev builds on
  GitHub Packages, which nothing had started since 2026-11-14, are gone from the workflow.

## 1.11.1-ext.5 (2026-11-16)

### Window modules (`org.thisisthepy.compose.window`)

- **Fix.** The mouse wheel scrolled the wrong way on the Kotlin/Native Windows and Linux
  windows, and too far. The windows send a scroll's travel in lines with a positive y asking
  for what is below, as AWT's wheel rotation, and desktop Compose (which the GraalVM windows go
  through) negates it. Foundation's `WindowsScrollConfig` (mingwX64) and `LinuxScrollConfig`
  (linuxX64, linuxArm64) did not. On Windows the sign had been right until #169 negated the
  window's vertical delta for the GraalVM path, which reached the Kotlin/Native config as well;
  that config also counted the lines a notch moves a second time, three times too far at the
  default. On Linux the config had the wrong sign from the start and moved 64 dp a line, 192 dp
  a notch. Both now answer as desktop Compose does on the same system, with its line: a
  twentieth of the scrolled side on Windows, the square root of it on Linux. The windows are
  unchanged, so the GraalVM path is not negated twice. `WindowEvent.SCROLL` holds a table of
  what each window sends and what each config makes of it. This changes foundation.
- **Fix.** Nothing scrolled on the Kotlin/Native macOS window, with a wheel or a trackpad. Two
  things were wrong. The scene sent every scroll to the top left corner of the window, because
  the window's scroll record carries the travel where a pointer event carries its place, and
  Compose hit-tests a scroll at its position; only what lay under the corner could scroll. And
  Compose's macOS scroll distance (`MacOsScrollConfig` in foundation) was worked out from the
  `NSEvent` alone, which this window does not hand the scene, so every scroll came to zero. The
  scene now remembers where the last move, press, release or enter put the pointer and sends the
  scroll there, and the macOS window tells it where the pointer is before a scroll that came
  before any move. Foundation's macOS config, given no `NSEvent`, now reads the travel from the
  pointer change: a line is 10 dp, NSScrollView's line, and ten trackpad points are a line, so a
  trackpad moves the content as far as the fingers went. A scroll with an `NSEvent` is worked out
  as before. `WindowEvent.SCROLL` documents the units and the sign every window layer sends. This
  changes foundation, so a consumer needs a Compose build from this commit on.
- **Fix.** On the Kotlin/Native macOS window, a native view moved during an animated scroll was
  a frame ahead of the content drawn under it. `MacosNativeViews` placed each slot inside a Core
  Animation transaction of its own, which, with no transaction open around it, went to the
  window server at once, in the middle of the frame, while the drawable of that frame went with
  the run loop's transaction at the end of the turn. The slot is now placed in the thread's
  transaction, so the view and the frame reach the screen in the same composition. A test reads
  what the window server composited through ScreenCaptureKit: 31 to 44 of about 112 pictures of
  a moving slot had the two apart before, none after. (#233)
- **Fix.** On the Kotlin/Native macOS window, a native view could still reach the screen a
  frame ahead of the frame drawn under it in a key window. The window's content view is an
  `NSVisualEffectView` that follows the window's active state, and in a key window its blur of
  what is behind the window is live; while it is, the window server does not always composite
  the layer tree and the Metal drawable of one commit together. The slot test had passed
  because its window was never key. `MacosNativeViews` now holds the effect view inactive while
  any slot is shown, and hands it back to the window's active state when none is, so a window
  with a native view shows the material as a window that is not key does. The same-frame test
  now runs in a key window, without a scene, with an idle scene and with a scene that places
  the view itself: 1 to 7 of 82 to 94 pictures had the two apart before, none of 77 to 94
  after. (#233)
- **Fix.** Placing a macOS native view cost the same whether anything changed or not.
  `MacosNativeViews.setFrame` and `setVisibleRegion` each placed the slot from scratch, set both
  frames, `masksToBounds` and the mask again, and made a new `CAShapeLayer` and `CGPath` for any
  clipped slot, holes or not. A placing now returns at once for the input it last placed with,
  sets a frame only when it differs, makes no mask for a clip with no hole inside it (the
  container's bounds are the clip), and makes a mask again only when what it cuts changed,
  reusing the previous one when a frame and then a region put the same cut back. New:
  `NativeViews.setFrameAndVisibleRegion`, both in one call and one placing; the two calls stay.
  Four clipped views told their frame and region every frame, in a debug build: 46 µs a call
  scrolling and 60 µs standing still before, 30 µs and 10 µs after, 39 µs a view through the
  combined call. (#233)
- **Fix.** A restore or a snap of the Kotlin/Native Windows window showed one frame at the wrong
  size almost every time. The frame drawn ahead is now presented through the swapchain and shown
  once the window has taken its new size, after DWM has read the windows; the probe allows at
  most 10 % of size changes a faulty frame, and a few in a hundred remain until a surface takes
  size and picture in one step (#223). (#232)
- **New.** A Windows window can give its caption strip to the application:
  `dxc_native_window_configure_app_caption` (`Win32NativeWindow.appCaption`) makes the strip's
  empty part client area, so the application's own buttons there get a press, and
  `dxc_native_window_begin_drag` (`Win32NativeWindow.beginDrag`) starts the system's move or
  resize loop from a strip or drag region the application chose. (#236)
- **New.** An application can declare a node's accessible role to the Kotlin/Native macOS window
  (`DeclaredElementRoles`), for roles Compose does not name (a group, a list, a tab list, a
  dialog). A node with a declared role is described even with nothing to read out, so VoiceOver
  reaches the buttons inside a labelled container. (#234)

### Publishing

- The "Extended publish" workflow stopped at "Decide the mode" because the plan job did not check
  out `extended/skiko`, which the bundle tool's test runs. (#229)
- The publish workflows' required inputs are booleans again, so a dispatch takes them. (#237)

## 1.11.1-ext.4 (2026-11-14)

### Window modules (`org.thisisthepy.compose.window`)

- **Fix.** A right click in a text field of a macOS window put up two menus. On Kotlin/Native,
  `MacosWindowPlatform` put up an edit menu of its own beside the `NSMenu` Compose already puts
  up there; `nativeEditMenu` is now off by default, as `MacosWindow.editMenuOnRightClick` was.
  On the GraalVM window, `AppKitWindowPlatform` put up its own menu and the scene then drew
  Compose's menu as well; the window now sets no menu of its own and says its menus are the
  system's (`WindowPlatform.systemContextMenu`, new, false by default), and a JVM scene hands
  such a platform what Compose's menu holds through `LocalContextMenuRepresentation`, entries
  and greying as the field says, and draws none. On both, an application that gives its own
  menu (a dropdown or toolbar provider on Kotlin/Native, `LocalContextMenuRepresentation` or
  `LocalTextContextMenu` on the JVM) shows that one alone. Tests on real windows count the menus
  that come up, and cover the editing shortcuts, the Control letters, the minimum size, the Dock
  icon, dark mode followed while open and the clipboard. `MacosWindowPlatform.close()` on a
  platform made with its own content no longer closes the scene twice, which threw.
  (DarkPyonix/compose-rust#180, #146, FR-33.1)
- **Fix.** After a change of scale (`Xft.dpi` rewritten in the root window's resources) the X11
  windows kept their size in pixels, so a 640x480 window at a scale of 1 was 320x240 points at
  2. Both X11 layers (Kotlin/Native and GraalVM) now keep the size in points, as the macOS and
  Windows windows do: the window is resized to its size in points times the new scale
  (`X11Scale.sizeAtScale` in `common`, `dxc_pixels_at_scale` in `x11_window.c`), its top left
  staying put, its size hints are told again in the new pixels, and it measures at the new size
  and scale before the frame it asks for. A fullscreen window keeps the screen's size.
  (DarkPyonix/compose-rust#18, FR-82)
- **Fix.** A right click in a text field of a scene with no menu of its own (the X11 windows)
  put up two menus: Compose's own text context menu and one the scene drew beside it with
  every entry enabled. The scene's is gone; the menu is Compose's, with the entries the field
  allows, in the platform's language. The scene adds the keyboard's way to it: the Menu key or
  Shift and F10 over a focused field open the field's menu at the caret, keeping the selection
  (on the JVM through the menu's own state, on Kotlin/Native by a secondary press inside the
  selection). Escape closes a popup or a menu (the scene's own back dispatcher), and on
  Kotlin/Native, whose menu moves only with Tab, the arrow keys step through an open menu.
  (DarkPyonix/compose-rust#142)
- **New.** The Windows window has a text edit menu of its own, the system's popup menu, for an
  application that hosts Compose on it: `Win32WindowPlatform.setEditMenu` and
  `Win32GraalWindow.setEditMenu` (`dxc_native_set_edit_menu`) turn it on while a text field has
  focus, with what the field allows (`EditMenuState` in `common`). A right click, the Menu
  key or Shift and F10 put up Cut, Copy, Paste and Select All, greyed where they cannot run,
  labelled by the system's edit control, at the pointer or at the caret; the entry chosen
  arrives as Control and X, C, V or A. (DarkPyonix/compose-rust#142)
- **Fix.** The JVM classes of `common`, `scene`, `graalvm-macos` and `graalvm-windows` are Java 11
  (class major 55) again, as the fork's Compose is; 1.11.1-ext.3 shipped them at Java 21 (65).
  `graalvm-linux` is Java 17 (61): its D-Bus connection uses `UnixDomainSocketAddress`.

### Design systems (`org.thisisthepy.compose.designsystem`)

- **Fix.** The JVM and Android classes are Java 11 (class major 55). 1.11.1-ext.3 shipped them at
  Java 25 (69), which a JDK 17 or 21 consumer cannot load (`UnsupportedClassVersionError`) and
  ASM 9.7 cannot read.

### Publishing

- `central_bundle.py validate` refuses a release whose jars or aars hold a class file above
  major 55, except the modules listed in `CLASS_MAJOR_EXCEPTIONS` (`graalvm-linux`, 61).

## 1.11.1-ext.3 (2026-11-13)

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
- **Fix.** On KDE Plasma (X11), text was scaled twice whenever fonts DPI was forced, which
  Plasma's display settings also do themselves at any global scale other than 100%: Plasma
  writes `forceFontDPI` from `kcmfonts` into `Xft.dpi`, the X11 windows took their scale from
  that, and they reported `forceFontDPI` / 96 as the text scale on top (so 100% with fonts at
  120 DPI was 1.25 x 1.25, and 200% without forcing anything was 2 x 2). The text scale is now
  `forceFontDPI` / (96 x the global scale of `kdeglobals` `[KScreen] ScaleFactor`), and both X11
  windows take it out of `Xft.dpi` with the GNOME rule of `X11Scale.withoutTextScale`, given
  that global scale, so the window scale is Plasma's global scale (fractional ones too) and
  text is scaled once. On a KDE session (`XDG_CURRENT_DESKTOP`) KDE's setting now comes before
  `gsettings`, which is often installed there and answers its default. `GDK_SCALE` still wins.
  (#210, DarkPyonix/compose-rust#18)
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
