/*
 * The `buttons` field of a pointer event, as common's WindowEvent defines it: the buttons
 * held down in the low bits, and DXC_SECONDARY_BUTTON on the press or release of the
 * secondary button. The values mirror WindowEvent.PRIMARY_HELD, SECONDARY_HELD, MIDDLE_HELD
 * and SECONDARY_BUTTON; test/win32_shared_test.c pins them.
 *
 * Header-only logic with no Win32 calls, so the test compiles without a window.
 */
#ifndef DXC_WIN32_BUTTONS_H
#define DXC_WIN32_BUTTONS_H

#include <stdint.h>

#define DXC_PRIMARY_HELD 1
#define DXC_SECONDARY_HELD 2
#define DXC_MIDDLE_HELD 4
#define DXC_SECONDARY_BUTTON (1 << 16)

/** The `buttons` of an event: what is held, and whether the event is the secondary button's own. */
static inline int32_t dxc_pointer_buttons(int32_t held, int secondary_event) {
    return secondary_event ? (held | DXC_SECONDARY_BUTTON) : held;
}

#endif
