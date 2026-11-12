# windows-shared

The Windows window: one C implementation, linked by both ways of shipping a Compose
application for Windows without a JVM.

- `c/win32_window.c`: Win32, a Direct3D 12 swapchain, IMM32 composition, UI Automation, the
  clipboard, file drops and the live resize. `c/win32_file_dialog.c`: the file open and save
  dialogs. `c/win32_notifications.c`: toasts.
  `c/win32_resize.h`, `c/win32_ime_text.h`, `c/win32_buttons.h`, `c/win32_wheel.h` (the wheel's scroll
  direction) and `c/win32_file_dialog_spec.h` (a dialog's type list) are header-only logic that compiles without
  Windows.
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
