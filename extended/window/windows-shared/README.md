# windows-shared

The Windows window: one C implementation, linked by both ways of shipping a Compose
application for Windows without a JVM.

- `c/win32_window.c`: Win32, Direct3D 12 frames shown through DirectComposition, IMM32
  composition, UI Automation, the clipboard, file drops and the live resize.
  `c/win32_dcomp.cpp` (with `c/win32_dcomp.h`) is its DirectComposition half, the one C++ file. `c/win32_file_dialog.c`: the file open and save
  dialogs. `c/win32_notifications.c`: toasts.
  `c/win32_resize.h`, `c/win32_ime_text.h`, `c/win32_buttons.h`, `c/win32_wheel.h` (the wheel's scroll
  direction), `c/win32_dpi.h` (the size a window keeps across a change of DPI) and
  `c/win32_file_dialog_spec.h` (a dialog's type list) and `c/win32_native_views.h` (where a native
  view slot goes and which focus changes it reports) are header-only logic that compiles without
  Windows.
  `c/win32_keys.h` decides which key messages stay the scene's (F10 and the menu key among
  them); it is header-only too and reads only the message and key names of `<windows.h>`.
  `c/win32_edit_menu.h` holds what the text edit menu offers and which keys ask for it; it
  compiles without Windows.
  `c/win32_uia.h` decides what UI Automation is told about an element: which control patterns
  it offers (Invoke, Toggle, Value, RangeValue, SelectionItem, ExpandCollapse), how the
  elements' parent ids make a tree, what Expand and Collapse become, and the runtime id that
  keeps a control the same element across pushes. It compiles without Windows.
- `cinterop/include/win32_window.h`: the whole surface, functions and records. It includes
  no Windows header, so cinterop and any C compiler read it.
- `cinterop/win32_window.def` and `src/`: the Kotlin/Native (`mingwX64`) layer.
  `Win32NativeWindow` opens the window and hands out frames; `Win32Loop` pumps messages and
  drains events into `common`'s `WindowEvent`; `Win32Records` reads and writes the records.
  `WindowsFileDialogs` is `common`'s `FileDialogs` for a window.
  It names no Compose type: what draws into a frame is the application's.

## Linking

The C is compiled with MSVC (`cl /c /std:c11`) and linked by MSVC, for both consumers.
`c/win32_dcomp.cpp`, the window's DirectComposition calls, is the one C++ file: it is
compiled with `cl /c /std:c++17 /GR- /EHs-c-` and its object goes beside the others.

- GraalVM native image: the objects are passed to the image's link, and the image's Kotlin
  calls them by symbol name with `@CFunction`.
- Kotlin/Native: `mingwX64` is only the name of Kotlin/Native's Windows target. The
  application is an MSVC executable, and its final link, which puts the MSVC objects of this
  window beside the Kotlin/Native object, is MSVC's. There is no MinGW link of this window.

The application links `user32`, `gdi32`, `ole32`, `oleaut32`, `imm32`, `uiautomationcore`,
`d3d12`, `dxgi`, `dxguid`, `shell32`, `advapi32` and `runtimeobject`. `dwmapi`, `d3d11` and
`dcomp` are loaded at run time, so linking `dcomp.lib` is not needed. The toasts call
`compose_rust_renderer_request_frame(void)` when an answer arrives, which the application
defines.

## How frames reach the screen

The window has two routes. It takes the GDI route unless `DXC_DCOMP=1` asks for the
DirectComposition one and the machine can give it, and says which on standard error
(`compose-rust: dcomp: route dcomp` or `route gdi`, with the reason). Its memory lines end in
`route=dcomp` or `route=gdi`.

The GDI route is the default because of what a real GPU showed (2026-11-21, a real
application dragged by hand): the DirectComposition route left a see-through strip inside the
border on a fast drag that grows the window, and the picture slid on a drag of the left or top
edge. The GDI route showed neither. See "Known faults" under the DirectComposition route.

### The DirectComposition route

The window is made with `WS_EX_NOREDIRECTIONBITMAP`, so it has no GDI surface. One
DirectComposition visual on it shows a virtual surface, and every frame is written into a
surface and committed. The frame, its size and the visual's clip reach DWM in that one commit,
which a swapchain cannot do: a present reaches DWM on its own, a composition before or after
the commit that sizes it (issue #223).

- Two virtual surfaces take turns. A frame goes into the one the visual does not show, and the
  visual is given it in the commit that shows the frame.
- The surfaces are written with a Direct3D 11 device of their own on the window's adapter. A
  CPU frame is one `UpdateSubresource`. A GPU frame reaches that device through the draw
  texture, shared from Direct3D 12, and a shared fence, so nothing waits on the CPU. Written
  with the D3D11On12 device instead, each write drained the Direct3D 12 queue first (42 ms a
  drag step on WARP).
- The swapchain made on this route is never shown. It is the handle the renderer passes back
  with each frame.

A size change (maximise, restore, snap, Win and an arrow, a change of DPI, a step of an edge
drag) has its frame drawn on the CPU in `WM_NCCALCSIZE`, before the window takes the new
rectangle. A commit reaches the screen with that rectangle or a composition after it, never
reliably with it. A frame smaller than its window then leaves a margin inside the window's
border for one composition, which shows nothing wrong; a frame larger than its window is cut
by it. So the frames are committed in this order:

- A size that only grows: the new frame is committed in `WM_SIZE`, after the rectangle.
- A size that only shrinks: the new frame is committed in `WM_NCCALCSIZE` and waited for until
  DWM has taken it (`WaitForCommitCompletion`), before the window takes the smaller rectangle.
- A size that grows one side and shrinks the other (a snap to a half, and back): one more frame
  is drawn, of the size both rectangles hold (the smaller width and the smaller height), and
  committed and waited for first; the new frame follows in `WM_SIZE`.

The wait before each drag step that the GDI route takes on builds below 22000 (issue #245) is
not taken here: the order already decides which frame meets which rectangle.

#### Known faults

The window's rectangle is not part of the commit. The rectangle reaches DWM from the window
manager and the picture from DirectComposition, and the two can land in different
compositions. Two things follow, both seen on a real GPU and neither seen by the probe, which
drags the right edge slowly and takes a see-through margin for a smaller window:

- A step that grows is committed after the window grew. A composition in between shows the
  larger window with the smaller picture, and with no GDI surface the rest is see-through.
- The surface sits at the window's top left. A drag of the left or top edge moves that
  origin, so for a composition the picture is drawn for one origin and shown at the other:
  what should stay still on the right moves by the step. A step that shrinks commits first
  and waits, so there it happens on every step.

The probe's judged drags (below) look for both.

### The GDI route

The default. `DXC_DCOMP` unset or not `1`, a machine without a composition device, or a
composition surface that cannot be
put on the window (the window is then made again) gives the route the window had before: the
GDI redirection surface, and the swapchain on a child window that covers the client area and
passes every press to the window. During a size change the child is hidden, and each CPU frame
is copied into the window with `BitBlt`. A size change outside a drag still shows the new size
with the old picture now and then (issue #223); the probe below counts it.

### Switches

Read when a window opens. The defaults are what the probe runs below measured.

| Variable | Values | What it does |
|---|---|---|
| `DXC_DCOMP` | `1` | The DirectComposition route. Anything else, or unset, is the GDI route. |
| `DXC_DCOMP_SURFACES` | `1`, `2` (default), `3` | How many surfaces take turns. |
| `DXC_DCOMP_UPLOAD` | `on12` | Write the surfaces with the D3D11On12 device. |
| `DXC_DCOMP_COMMIT` | `order` (default), `ahead`, `wmsize` | The commit order above, every frame in `WM_NCCALCSIZE`, or every frame in `WM_SIZE`. |
| `DXC_DCOMP_SHRINK_WAIT` | `commit` (default), `flush`, `none`, `hold` | What a frame shown before a smaller rectangle waits for: DWM taking the commit, that and one composition, nothing, or nothing with the window held at the last frame DWM took during a drag. |
| `DXC_DCOMP_DRAG_RULE` | `1` | Take the GDI route's wait before each drag step. |
| `DXC_REPORT_LATENCY` | any | Each resize step and each drag print where their time went. The drag step lines also carry the window's left and top (`left= top=`), the edge dragged (`edge=`) and, on the DirectComposition route, whether the step's frame was `committed first` (before the rectangle) or `committed in WM_SIZE`. |

### Measured

CI run 44666182474 on the GitHub runners, which draw with WARP (software), by the resize probe
(`c/test/win32_resize_probe.c`):

| | windows-2022 DirectComposition | windows-2022 GDI | windows-2025 DirectComposition | windows-2025 GDI |
|---|---|---|---|---|
| Faulty frames in 247 maximise, restore and snap changes | 0 | 11 | 4 (maximise) | 81 |
| Faulty frames in edge drags | 0 | 0 | 0 | 0 |
| Drag lag, median / p95 / max px, longest hold | 16 / 40 / 40, 63 ms | 0 / 8 / 16, 31 ms | 16 / 40 / 48, 78 ms | 0 / 0 / 8, 31 ms |
| Same, with 30 ms frames | 24 / 48 / 56, 93 ms | 24 / 32 / 32, 93 ms | 24 / 56 / 56, 123 ms | 16 / 24 / 24, 56 ms |
| Working set at rest | 31.2 MB | 33.2 MB | 37.9 MB | 40.1 MB |

On WARP the DirectComposition route trails the GDI route in a drag by about one composition: a
drag step's `BeginDraw` waits for DWM's next composition (about 20 ms on the runners), and a
shrinking step's commit wait for DWM to take it (about 45 to 53 ms). A GDI step waits on
nothing of DWM's. How this looks on a real GPU is still to be measured, with the lag probe
below.

## Native views

`WindowsNativeViews` (common's `NativeViews`, compose-rust FR-81), over `dxc_native_view_*` in
`c/win32_window.c`, with its arithmetic and focus rules in `c/win32_native_views.h`. The
application makes an HWND against `dxc_native_view_parent` (the window) and hands it over; the
window puts it in a container, a child window of its own with `WS_CLIPSIBLINGS`, above every
slot attached before it. The container is the slot's frame cut to its clip; the view inside
keeps the whole frame, so a view half scrolled out shows the half that is in. Holes are a window
region on the container, which also sends a click in a hole to the scene. Positions are client
pixels; a change of DPI needs nothing here, the caller lays out again and sends the new frames.

What is asked for during a frame is given to the windows after that frame is presented
(`dxc_native_frame_end`), copied (`dxc_raster_copy`, during a size change) or committed (the
DirectComposition route), and otherwise at the end of the next pump.

- GDI route: the swapchain's child window is given `WS_CLIPSIBLINGS` with the first slot, so it
  is cut around the containers above it. While a size changes it is hidden and the frame is
  copied into the window with GDI, cut around the slots (`dxc_nv_exclude`), which draw into
  the same GDI surface. The slots are never hidden with it.
- DirectComposition route: the visual's target is made below the window's child windows
  (`CreateTargetForHwnd` with topmost false), so DWM composes the slots above the frame. The
  window has no GDI surface, so a container is a layered child window (Windows 8 and later, as
  the application's manifest declares) for a view that draws with GDI; where one cannot be
  made the window says so on standard error.

Focus is read from the system at the end of every pump (`GetFocus`) and reported as
`NATIVE_VIEW_FOCUS_IN` and `_OUT`. A press in the scene gives the keyboard back to it;
deactivated, the window reports the slot's loss, and activated again gives the slot the
keyboard back and reports it. Over a slot the window sets no cursor and the scene hears
`POINTER_EXIT`, then `POINTER_ENTER` when the pointer comes back. A view leaves hidden, under the
message-only window, for the application to destroy; the window takes every view out this way
when it closes. The shared test runs it on a real window on both routes
(`fr81_native_views_in_a_real_window`).

## File dialogs

`dxc_native_file_dialog_show` shows the Common Item Dialog (`IFileOpenDialog`,
`IFileSaveDialog`) owned by a window and returns at once. `IFileDialog::Show` is a modal call
with a loop of its own, so each dialog runs on a thread of its own (a single threaded
apartment) with the window as its owner: the window's thread keeps pumping and drawing, and
only input to the owning window waits, which is what the owner being disabled by `Show` means.
The answer is written on a list and the asking thread is woken with a thread message; it reads
the list after its pump (`dxc_native_file_dialog_next`, `dxc_native_file_dialog_take`), so
nothing of the caller's runs on the dialog's thread. A dialog is ended early
(`dxc_native_file_dialog_cancel`, or its owner being destroyed) from a timer of the dialog's own
thread, and then answers cancelled. Every dialog that started answers exactly once.

Paths come back absolute, in UTF-8, whole (no `MAX_PATH` cut), a NUL between one and the next.
A selection that cannot be carried whole (a name with no UTF-8 form, more than 10000 paths,
more than 32 MiB) answers unavailable rather than a part of it.

In Kotlin/Native, `WindowsFileDialogs { hwnd }` implements `common`'s `FileDialogs`: requests
for one window are shown in order, and each result is delivered once on the thread that pumps,
from `Win32Loop.deliverFileDialogs()` (called by `Win32Windows.pump`; a caller that pumps with
`Win32Loop.pump` calls it after). `Win32WindowPlatform.fileDialogs` is the same for a platform
window and cancels its dialogs when the window closes.

## The caption

A window opened with `system_chrome` 0 (`dxc_native_window_configure`; `WindowChrome.Modern`
through `Win32WindowPlatform`) keeps the system's frame and gives its caption strip to the
content. Nothing here draws: the scene draws minimise, maximise or restore and close at the
trailing end of the strip, 46 by 32 points each (`common`'s `Win32Chrome`, the same numbers as
`c/win32_caption.h`, held together by `scripts/check-windows-caption-metrics.sh`).

`WM_NCHITTEST` answers for the strip (`dxc_caption_zone_at`): the top band resizes, an empty
part moves the window, minimise and close are client area and take ordinary mouse input, and
maximise is `HTMAXBUTTON`, which is the only way Windows 11 shows Snap Layouts over it. The
maximise button's non-client moves and its press are passed to the scene as pointer events at
the same point; the press captures the mouse, so its release arrives like any other. When the
pointer is over nothing the scene draws any more, the scene hears one move to (-1, -1), which
ends the hover (`dxc_caption_nc_move` and the three beside it decide when).
`dxc_native_window_is_zoomed` says which glyph the maximise button shows.

A window nobody configured keeps the system's caption.

## The text edit menu

An application that hosts Compose on this window and draws no text context menu of its own
turns the window's on with `dxc_native_set_edit_menu` (`Win32WindowPlatform.setEditMenu`,
`Win32GraalWindow.setEditMenu`) while a text field has focus, saying what the field allows.
A right click, the Menu key or Shift and F10 then put up the system's popup menu: Cut, Copy,
Paste and Select All, labelled as the system's own edit control labels them (English where the
system has no such menu), greyed where the field or the clipboard says the entry cannot run.
It opens at the pointer, or for the keyboard at the caret given to `dxc_native_set_ime_spot`.
The entry chosen is pushed as Control and X, C, V or A, pressed and let go, so the field
reads it as it reads the keyboard. `TrackPopupMenuEx` runs the menu's own loop until it
closes, which dispatches this window's messages but not the application's frame loop: the
window shows its last frame while the menu is up, as under any Windows menu.

## The live resize

While the reader drags an edge, Windows runs its own loop inside the message that began the
drag. The window draws the frame for each new size itself, on the CPU: the application's draw
callback renders it with Skia's raster backend into a DIB (`dxc_native_raster_begin`), in
`WM_NCCALCSIZE` before the window takes the size. On the DirectComposition route the frame is
written into a surface and committed in the order above. On the GDI route it is copied into
the window with `BitBlt` in `WM_SIZE` and the window is validated, with the swapchain's child
hidden. Each step prints one
`resize-proof step WxH presents=N child_visible=0 blit=1 validated=1` line to standard error,
and each drag prints how many steps were drawn ahead. `DXC_METRICS` adds the memory lines
(`memory-breakdown ...`), and `DXC_CAPTURE_CHECK` compares what DWM composed, read back
through Desktop Duplication, with the frame drawn for each step.

## The resize probe's judged drags

`c/test/win32_resize_probe.c` has four modes for the faults above, which its right-edge drag
cannot see:

- `drag-left`, `drag-top`, `drag-topleft`: the left edge, the top edge and the top-left
  corner dragged in and out, twice each.
- `drag-fast`: the window grown fast by its right edge, its bottom edge, its bottom-right
  corner and its top-left corner.

The pointer moves 40 to 120 px every 4 to 8 ms (a real hand's pace on a real GPU), from a
fixed seed, so runs are comparable. The picture has an 8 px marker 16 px in from each corner
and a row of blocks spelling its width and height (`c/test/win32_probe_judge.h`, checked by
`c/test/win32_shared_test.c`). The probe has DWM draw the window's border in pure blue
(`DWMWA_BORDER_COLOR`), so each composed frame shows the window's true rectangle whatever the
picture does, and judges every frame of the drag against it:

- anchor: the right markers 16 px inside the border's right side and the bottom markers 16 px
  inside its bottom, within 1 px (both distances measured at rest first). Otherwise the frame
  is faulty, "shifted by N px".
- see-through: no backdrop pixel on 14 rows and 14 columns across the area inside the border,
  and the size the picture spells is the size inside the border. Otherwise "see-through N px
  at the right" (or another edge), or "frame of another size".

Every frame counts; there is no allowance for one frame. Each run prints
`probe: anchor <mode> judged N faulty M max shift S px ...` and
`probe: see-through <mode> judged N faulty M max P px at the <edge> ...`. The border colour
needs Windows build 22000 or later: on Windows 10 and Server 2022 the two lines say
`not available on this build` instead, and nothing is judged. The CI runners draw with WARP,
where a drag step waits for a composition in `BeginDraw`, so a step there lasts a composition
and the see-through fault shows less often than on a real GPU; the lag probe below runs the
same modes on a real machine.

## Measuring on a real GPU

`c/test/run-lag-probe.ps1` measures the drag lag and the faulty frames of both routes on the
machine it runs on, and then runs the judged drags above on both routes. CI runs it in its
short form, so it keeps working.

Without a compiler, use the prebuilt probe: the workflow's windows-2022 job uploads the
artifact `lag-probe-windows-x64`, with `win32_resize_probe.exe` (static CRT, so it needs only
the DLLs Windows ships), the script and `run-lag-probe.cmd`. Unzip it, double-click
`run-lag-probe.cmd`, leave the mouse and keyboard alone for about six minutes, and send back
the `lag-probe-summary.txt` it writes in the same folder.

To build it yourself:

1. Install Visual Studio 2022 Build Tools with "Desktop development with C++" and a Windows
   SDK.
2. Open "x64 Native Tools Command Prompt for VS 2022" and go to the repository's root.
3. Run:

   ```
   powershell -ExecutionPolicy Bypass -File extended\window\windows-shared\c\test\run-lag-probe.ps1
   ```

4. Leave the mouse and keyboard alone for about six minutes. The script builds the window
   and the probe, then moves a window and the pointer on screen: three kinds of drag, three
   times each, on each route, then the four judged drags, three times each, on each route.
5. Send back `extended\window\windows-shared\out\lag-probe\lag-probe-summary.txt`. It has
   the GPU, the Windows build and one row a run: route, faulty frames, lag median, p95 and max,
   the longest hold, and the window's memory at rest. A second table has one row a judged
   drag: route, mode, composed and judged frames, frames shifted and the largest shift, frames
   see-through and the widest strip with its edge, frames of another size, and the median time
   between pointer moves. The logs beside it have every line, the window's step lines
   (`DXC_REPORT_LATENCY`) included.

`-Runs 5` runs each drag five times; `-Fast` once.

## Checks

`.github/workflows/extended-window-windows-shared.yml` compiles the C with MSVC, links
`c/test/win32_shared_test.c` against it (record sizes, the resize and UTF-16 helpers, a file
dialog started, cancelled and answered on a real window, a UI Automation reader acting on a
real window, and that every declared symbol resolves), checks that the header and the C export the same functions,
and builds and tests the Kotlin/Native klib. On windows-2022 and windows-2025 it runs the
resize probe on both routes. The DirectComposition route fails the job above 2 % faulty frames
in 247 maximise, restore and snap changes, and on any faulty frame in a drag drawn ahead. The
GDI route is printed beside it for comparison. The judged drags run three times a mode on both
routes: the GDI route, the default, fails the job above 2 % of its judged frames shifted,
see-through or of another size, and on windows-2025 when the judges are not available or
judged nothing. The DirectComposition route's judged drags are printed, not gated, until its
fix lands. The first run with the judges (44920692034, windows-2025, WARP) counted 1 faulty
frame of 163 on the GDI route (the window grown by its left edge and the copy not yet in it,
109 px see-through) and 79 of 201 on the DirectComposition route (shifted by up to 382 px,
see-through up to 666 px).

The reader is `c/test/win32_uia_client.cpp`, in C++ because the SDK's `UIAutomationClient.h`
defines its ids as file-scope constants: a second C file including it would define each one
again beside `win32_window.c` and fail to link.

Source: compose-rust PR #174 (`feat/win32-resize-kn`) at 2d6ad3e3, where the C is
`renderer/desktop/c/` and the Kotlin/Native window is `renderer/windows/src/Win32Window.kt`.

## What a reader can ask

UI Automation reads the tree from the last `dxc_native_set_accessibility` push: each element
carries its id and its parent's, its states and actions, its value, range and hint, in window
points. A reader's request (Invoke, Toggle, SetValue, Select, Expand, Collapse, SetFocus) is not
done in the window. It is written to the window's event queue as
`DXC_EVENT_ACCESSIBILITY_ACTION`, naming the element's id and the action, and the scene does it
when the renderer drains the queue, on the same thread as a click. A new value is kept beside
the event, because it can be longer than an event's text, and read with
`dxc_native_take_accessibility_argument`, one per SET_VALUE event, oldest first.
