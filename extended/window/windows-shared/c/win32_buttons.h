/*
 * The `buttons` field of a pointer event, as common's WindowEvent defines it: the buttons
 * held down in the low bits, and the event's own button flag on its press or release; the
 * primary button has none. The values mirror WindowEvent's *_HELD and *_BUTTON constants;
 * test/win32_shared_test.c pins them.
 *
 * Header-only logic with no Win32 calls, so the test compiles without a window.
 */
#ifndef DXC_WIN32_BUTTONS_H
#define DXC_WIN32_BUTTONS_H

#include <stdint.h>

#define DXC_PRIMARY_HELD 1
#define DXC_SECONDARY_HELD 2
#define DXC_MIDDLE_HELD 4
#define DXC_BACK_HELD 8
#define DXC_FORWARD_HELD 16
#define DXC_SECONDARY_BUTTON (1 << 16)
#define DXC_MIDDLE_BUTTON (1 << 17)
#define DXC_BACK_BUTTON (1 << 18)
#define DXC_FORWARD_BUTTON (1 << 19)

/**
 * The `buttons` of an event: what is held, and the event's own button flag (one of the
 * DXC_*_BUTTON values, or 0 for the primary button and for events that are not a press or
 * release).
 */
static inline int32_t dxc_pointer_buttons(int32_t held, int32_t event_button) {
    return held | event_button;
}

#endif
