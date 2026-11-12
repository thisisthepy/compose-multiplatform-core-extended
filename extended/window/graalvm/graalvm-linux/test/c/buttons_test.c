/*
 * The `buttons` of a pointer event as x11_buttons.h builds them, against the values common's
 * WindowEvent names (PRIMARY_HELD 1, SECONDARY_HELD 2, MIDDLE_HELD 4, SECONDARY_BUTTON
 * 1 shl 16). Needs the X11 headers and no display. Exit status 0 means every check passed.
 */
#include <stdio.h>

#include "../../c/x11_buttons.h"

static int failures;

#define CHECK(condition) \
    do { if (!(condition)) { fprintf(stderr, "FAIL: %s (line %d)\n", #condition, __LINE__); failures++; } } while (0)

int main(void) {
    CHECK(DXC_PRIMARY_HELD == 1 && DXC_SECONDARY_HELD == 2 && DXC_MIDDLE_HELD == 4);
    CHECK(DXC_BACK_HELD == 8 && DXC_FORWARD_HELD == 16);
    CHECK(DXC_SECONDARY_BUTTON == (1 << 16) && DXC_MIDDLE_BUTTON == (1 << 17));
    CHECK(DXC_BACK_BUTTON == (1 << 18) && DXC_FORWARD_BUTTON == (1 << 19));

    CHECK(dxc_buttons(Button1Mask) == DXC_PRIMARY_HELD);
    CHECK(dxc_buttons(Button3Mask) == DXC_SECONDARY_HELD);
    CHECK(dxc_buttons(Button2Mask) == DXC_MIDDLE_HELD);

    // A right-button press: nothing held before it, the secondary button held and flagged after.
    int32_t press = dxc_pointer_buttons(0, 3, 1);
    CHECK(press == (DXC_SECONDARY_HELD | DXC_SECONDARY_BUTTON));
    // Its release: the server still reports the button held, the event lets it go and flags it.
    int32_t release = dxc_pointer_buttons(Button3Mask, 3, 0);
    CHECK(release == DXC_SECONDARY_BUTTON);
    // A left press carries no secondary flag, and a left release keeps a right button held.
    CHECK(dxc_pointer_buttons(0, 1, 1) == DXC_PRIMARY_HELD);
    CHECK(dxc_pointer_buttons(Button1Mask | Button3Mask, 1, 0) == DXC_SECONDARY_HELD);
    CHECK(dxc_pointer_buttons(0, 2, 1) == (DXC_MIDDLE_HELD | DXC_MIDDLE_BUTTON));
    CHECK(dxc_pointer_buttons(Button2Mask, 2, 0) == DXC_MIDDLE_BUTTON);
    CHECK(dxc_pointer_buttons(0, 8, 1) == (DXC_BACK_HELD | DXC_BACK_BUTTON));
    CHECK(dxc_pointer_buttons(0, 9, 0) == DXC_FORWARD_BUTTON);
    CHECK(dxc_pointer_buttons(Button1Mask, 3, 1) == (DXC_PRIMARY_HELD | DXC_SECONDARY_HELD | DXC_SECONDARY_BUTTON));

    if (failures == 0) printf("ok\n");
    return failures == 0 ? 0 : 1;
}
