#ifndef COMPOSE_WINDOW_CHROME_HOOK_H
#define COMPOSE_WINDOW_CHROME_HOOK_H

#include <Windows.h>
#include <stdint.h>

// Tells a Compose window that a frame of this size has just been presented into `content`, a
// child of it. A window holding its live resize for that frame lets go; any other window,
// and any window Compose did not set up, ignores it.
extern "C" void composeWindowChromePresented(HWND content, int32_t width, int32_t height);

#endif
