#ifndef DXC_WIN32_KEYS_H
#define DXC_WIN32_KEYS_H

// Which key messages the scene reads, and which of them the default handler sees after it.
//
// Kept apart from the window so the MSVC test can check the decision without a window. It
// needs <windows.h> included first.
//
// Anything held with Alt, and F10 alone, arrives as WM_SYSKEYDOWN and WM_SYSKEYUP. The
// default handler puts the window into menu mode on those: F10 or Alt let go selects the
// menu bar, and the next key is the menu's rather than the scene's. So every key message is
// the scene's and is answered here, F1 to F12, Shift with F10 and the menu key (VK_APPS)
// among them; Shift with F10 and the menu key are how a context menu is asked for, and the
// scene decides what to put up. The default handler's WM_CONTEXTMENU never comes either,
// because it would come from the same key up the scene already read.
//
// Alt with F4 is the exception: it is how a window is closed from the keyboard, and the
// handler that closes it is the default one. The scene is still told about the press.

/** Whether [message] is one of the four key messages, and a press rather than a release. */
static inline int dxc_key_is_press(UINT message) {
    return message == WM_KEYDOWN || message == WM_SYSKEYDOWN;
}

/**
 * Whether the default handler is to see this key message after the scene has: only the
 * press of Alt with F4.
 */
static inline int dxc_key_passes_to_system(UINT message, WPARAM key) {
    return message == WM_SYSKEYDOWN && key == VK_F4;
}

#endif
