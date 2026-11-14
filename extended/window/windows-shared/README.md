# windows-shared

The Windows window: one C implementation, linked by both ways of shipping a Compose
application for Windows without a JVM.

- `c/win32_window.c`: Win32, a Direct3D 12 swapchain, IMM32 composition, UI Automation, the
  clipboard, file drops and the live resize. `c/win32_file_dialog.c`: the file open and save
  dialogs. `c/win32_notifications.c`: toasts.
  `c/win32_resize.h`, `c/win32_ime_text.h`, `c/win32_buttons.h`, `c/win32_wheel.h` (the wheel's scroll
  direction) and `c/win32_file_dialog_spec.h` (a dialog's type list) are header-only logic that compiles without
  Windows.
  `c/win32_keys.h` decides which key messages stay the scene's (F10 and the menu key among
  them); it is header-only too and reads only the message and key names of `<windows.h>`.
  `c/win32_edit_menu.h` holds what the text edit menu offers and which keys ask for it; it
  compiles without Windows.
- `cinterop/include/win32_window.h`: the whole surface, functions and records. It includes
  no Windows header, so cinterop and any C compiler read it.
- `cinterop/win32_window.def` and `src/`: the Kotlin/Native (`mingwX64`) layer.
  `Win32NativeWindow` opens the window and hands out frames; `Win32Loop` pumps messages and
  drains events into `common`'s `WindowEvent`; `Win32Records` reads and writes the records.
  `WindowsFileDialogs` is `common`'s `FileDialogs` for a window.
  It names no Compose type: what draws into a frame is the application's.

## Linking

The C is compiled with MSVC (`cl /c /std:c11`) and linked by MSVC, for both consumers:

- GraalVM native image: the objects are passed to the image's link, and the image's Kotlin
  calls them by symbol name with `@CFunction`.
- Kotlin/Native: `mingwX64` is only the name of Kotlin/Native's Windows target. The
  application is an MSVC executable, and its final link, which puts the MSVC objects of this
  window beside the Kotlin/Native object, is MSVC's. There is no MinGW link of this window.

The application links `user32`, `gdi32`, `ole32`, `oleaut32`, `imm32`, `uiautomationcore`,
`d3d12`, `dxgi`, `dxguid`, `shell32`, `advapi32` and `runtimeobject`. `dwmapi` and `d3d11` are
loaded at run time. The toasts call `compose_rust_renderer_request_frame(void)` when an
answer arrives, which the application defines.

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
drag. The window draws the frame for each new size from inside `WM_SIZE`: the application's
draw callback renders it with Skia's raster backend into a DIB (`dxc_native_raster_begin`),
the C side copies it into the window DC with `BitBlt` and validates the window, and the
swapchain's child window stays hidden, so nothing is presented during the size change. Each
such step prints one
`resize-proof step WxH presents=0 child_visible=0 blit=1 validated=1` line to standard error.
`DXC_METRICS` adds the memory lines (`metrics memory ...`), and `DXC_CAPTURE_CHECK` compares
what DWM composed, read back through Desktop Duplication, with the frame drawn for each step.

The swapchain is created in one function, so changing how the pixels reach the screen touches
one place.

## Checks

`.github/workflows/extended-window-windows-shared.yml` compiles the C with MSVC, links
`c/test/win32_shared_test.c` against it (record sizes, the resize and UTF-16 helpers, a file
dialog started, cancelled and answered on a real window, and that every declared symbol resolves), checks that the header and the C export the same functions,
and builds and tests the Kotlin/Native klib.

Source: compose-rust PR #174 (`feat/win32-resize-kn`) at 2d6ad3e3, where the C is
`renderer/desktop/c/` and the Kotlin/Native window is `renderer/windows/src/Win32Window.kt`.
