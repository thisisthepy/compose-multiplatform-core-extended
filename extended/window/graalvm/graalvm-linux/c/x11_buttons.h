/*
 * The `buttons` field of a pointer event, as common's WindowEvent defines it: the buttons
 * held down in the low bits, and DXC_SECONDARY_BUTTON on the press or release of the
 * secondary button. The values mirror WindowEvent.PRIMARY_HELD, SECONDARY_HELD, MIDDLE_HELD
 * and SECONDARY_BUTTON; test/c/buttons_test.c pins them.
 *
 * A header with no state, so that the test can include it without a display.
 */
#ifndef DXC_X11_BUTTONS_H
#define DXC_X11_BUTTONS_H

#include <stdint.h>
#include <X11/X.h>

#define DXC_PRIMARY_HELD 1
#define DXC_SECONDARY_HELD 2
#define DXC_MIDDLE_HELD 4
#define DXC_SECONDARY_BUTTON (1 << 16)

/** The buttons a server's modifier state says are held. */
static inline int32_t dxc_buttons(unsigned int state) {
    return ((state & Button1Mask) ? DXC_PRIMARY_HELD : 0) |
           ((state & Button3Mask) ? DXC_SECONDARY_HELD : 0) |
           ((state & Button2Mask) ? DXC_MIDDLE_HELD : 0);
}

/**
 * The `buttons` of a press or release of `button` (the server's number: 1 primary, 2 middle,
 * 3 secondary) when `state` was held before it. The server reports the state before the
 * event, so the button is added on a press and removed on a release.
 */
static inline int32_t dxc_pointer_buttons(unsigned int state, unsigned int button, int press) {
    int32_t bit = button == 1 ? DXC_PRIMARY_HELD
                : button == 3 ? DXC_SECONDARY_HELD
                : button == 2 ? DXC_MIDDLE_HELD
                : 0;
    int32_t buttons = dxc_buttons(state);
    buttons = press ? (buttons | bit) : (buttons & ~bit);
    if (button == 3) buttons |= DXC_SECONDARY_BUTTON;
    return buttons;
}

#endif
