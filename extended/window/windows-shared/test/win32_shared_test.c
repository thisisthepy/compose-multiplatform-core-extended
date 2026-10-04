// Checks the parts of the shared Win32 window that are arithmetic, and that the objects
// the window is made of resolve every symbol the header declares. Compiled and run by
// the extended-window-windows-shared workflow with MSVC.
#include <stdio.h>
#include <string.h>

#include "win32_resize.h"
#include "win32_ime_text.h"
#include "win32_window.h"

static int failures;

#define CHECK(condition) \
    do { if (!(condition)) { printf("fail: %s (line %d)\n", #condition, __LINE__); failures++; } } while (0)

static void resize_decisions(void) {
    struct dxc_resize resize;
    memset(&resize, 0, sizeof resize);
    int32_t width = 0, height = 0;

    CHECK(dxc_resize_take(&resize, &width, &height) == 0);

    dxc_resize_fitted(&resize, 800, 600);
    dxc_resize_note(&resize, 800, 600);
    CHECK(dxc_resize_take(&resize, &width, &height) == 0);
    CHECK(resize.wanted_width == 0);

    dxc_resize_note(&resize, 1024, 768);
    CHECK(dxc_resize_take(&resize, &width, &height) == 1);
    CHECK(width == 1024 && height == 768);
    CHECK(dxc_resize_take(&resize, &width, &height) == 0);

    CHECK(dxc_resize_draw_here(&resize) == 0);
    dxc_resize_begin_drag(&resize);
    CHECK(dxc_resize_draw_here(&resize) == 1);
    dxc_resize_end_drag(&resize);
    CHECK(dxc_resize_draw_here(&resize) == 0);
}

static void utf8_conversion(void) {
    const uint16_t pair[] = { 0xd83d, 0xde00 };
    char out[16];
    CHECK(dxc_utf16_to_utf8(pair, 2, out, sizeof out) == 4);
    CHECK((unsigned char)out[0] == 0xf0 && out[4] == '\0');
    const uint16_t lone[] = { 0xd800 };
    CHECK(dxc_utf16_to_utf8(lone, 1, out, sizeof out) == 3);
    const uint16_t ascii[] = { 'h', 'i' };
    CHECK(dxc_utf16_to_utf8(ascii, 2, out, sizeof out) == 2);
    CHECK(strcmp(out, "hi") == 0);
}

int main(int argc, char **argv) {
    resize_decisions();
    utf8_conversion();
    // Never true. It only makes the linker resolve the window and the toasts, so a symbol
    // the header declares and the objects lack fails the link.
    if (argc > 1000) {
        struct dxc_native_window window;
        dxc_native_window_open(argv[0], 1, 1, &window);
        dxc_native_frame_begin(window.swapchain, NULL);
        dxc_native_poll_event(NULL);
        dxc_native_clipboard_write(argv[0]);
        dxc_notify_start();
    }
    if (failures == 0) printf("ok\n");
    return failures == 0 ? 0 : 1;
}
