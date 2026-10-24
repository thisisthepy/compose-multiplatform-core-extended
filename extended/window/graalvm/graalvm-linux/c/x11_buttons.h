/*
 * The `buttons` field of a pointer event, as common's WindowEvent defines it: the buttons
 * held down in the low bits, and the event's own button flag (DXC_SECONDARY_BUTTON,
 * DXC_MIDDLE_BUTTON, DXC_BACK_BUTTON, DXC_FORWARD_BUTTON) on its press or release; the primary
 * button has none. The values mirror WindowEvent's *_HELD and *_BUTTON constants;
 * test/c/buttons_test.c pins them.
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
#define DXC_BACK_HELD 8
#define DXC_FORWARD_HELD 16
#define DXC_SECONDARY_BUTTON (1 << 16)
#define DXC_MIDDLE_BUTTON (1 << 17)
#define DXC_BACK_BUTTON (1 << 18)
#define DXC_FORWARD_BUTTON (1 << 19)

/** The buttons a server's modifier state says are held. */
static inline int32_t dxc_buttons(unsigned int state) {
    return ((state & Button1Mask) ? DXC_PRIMARY_HELD : 0) |
           ((state & Button3Mask) ? DXC_SECONDARY_HELD : 0) |
           ((state & Button2Mask) ? DXC_MIDDLE_HELD : 0);
}

/**
 * The `buttons` of a press or release of `button` (the server's number: 1 primary, 2 middle,
 * 3 secondary, 8 back, 9 forward) when `state` was held before it. The server reports the state before the
 * event, so the button is added on a press and removed on a release.
 */
static inline int32_t dxc_pointer_buttons(unsigned int state, unsigned int button, int press) {
    int32_t bit = button == 1 ? DXC_PRIMARY_HELD
                : button == 3 ? DXC_SECONDARY_HELD
                : button == 2 ? DXC_MIDDLE_HELD
                : button == 8 ? DXC_BACK_HELD
                : button == 9 ? DXC_FORWARD_HELD
                : 0;
    int32_t buttons = dxc_buttons(state);
    buttons = press ? (buttons | bit) : (buttons & ~bit);
    if (button == 3) buttons |= DXC_SECONDARY_BUTTON;
    if (button == 2) buttons |= DXC_MIDDLE_BUTTON;
    if (button == 8) buttons |= DXC_BACK_BUTTON;
    if (button == 9) buttons |= DXC_FORWARD_BUTTON;
    return buttons;
}

#endif
