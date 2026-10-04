# windows-shared

The one C implementation of the Windows window, linked by both consumers:

- GraalVM native image: `c/win32_window.c` and `c/win32_notifications.c` compiled with MSVC
  (`cl /c /std:c11`), or the `dxc_win32_window.lib` the CI job publishes, plus
  `kotlin/Win32Window.kt` and `kotlin/Win32DrawCallback.java`, which call it by symbol name.
- Kotlin/Native (mingwX64): cinterop over `c/win32_window.h` using `cinterop/win32_window.def`.
  The application is an MSVC executable, so the object is the MSVC one, not a MinGW rebuild.

`c/win32_window.h` is the whole surface. `c/win32_resize.h` and `c/win32_ime_text.h` are
header-only logic that compiles without Windows.

The swapchain is created in `dxc_create_swapchain` and nowhere else, so changing how the
window's pixels reach the screen touches one function.

Source: compose-rust PR #134 (`feat/win-linux-window-default`).
