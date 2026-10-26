// Checks the parts of the shared Win32 window that are arithmetic, that the objects the
// window is made of resolve every symbol the header declares, and that the records have
// the sizes the Kotlin/Native reader (src/.../Win32Records.kt) assumes. Compiled and run by the
// extended-window-windows-shared workflow with MSVC.
#include <stddef.h>
#include <stdio.h>
#include <string.h>

#include "win32_resize.h"
#include "win32_ime_text.h"
#include "win32_wheel.h"
#include "win32_caption.h"
#include "win32_window.h"

// The records the Kotlin side reads and writes by offset. A field added on one side only is
// a reader that silently reads the wrong bytes, so the sizes are fixed here.
_Static_assert(sizeof(struct dxc_event) == 124, "struct dxc_event is 124 bytes");
_Static_assert(offsetof(struct dxc_event, text) == 28, "dxc_event text starts at 28");
_Static_assert(sizeof(struct dxc_element) == 116, "struct dxc_element is 116 bytes");
_Static_assert(offsetof(struct dxc_element, label) == 20, "dxc_element label starts at 20");
_Static_assert(sizeof(struct dxc_native_window) == 5 * sizeof(void *), "five pointers");

static int failures;

// The toasts ask the renderer for a frame; the renderer is not part of this check.
void compose_rust_renderer_request_frame(void) {}

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

static void wheel_direction(void) {
    float x = 1.0f, y = 1.0f;
    // A notch away from the reader is a scroll up, which Compose reads as a negative y.
    dxc_wheel_to_scroll(120, 0, 3.0f, &x, &y);
    CHECK(x == 0.0f && y == -3.0f);
    dxc_wheel_to_scroll(-120, 0, 3.0f, &x, &y);
    CHECK(x == 0.0f && y == 3.0f);
    // A tilt to the right is a positive x on both sides.
    dxc_wheel_to_scroll(120, 1, 3.0f, &x, &y);
    CHECK(x == 3.0f && y == 0.0f);
    // A touchpad step of a quarter notch stays a quarter, not zero.
    dxc_wheel_to_scroll(30, 0, 1.0f, &x, &y);
    CHECK(x == 0.0f && y == -0.25f);
    dxc_wheel_to_scroll(30, 1, 1.0f, &x, &y);
    CHECK(x == 0.25f && y == 0.0f);
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

// The frame time is the performance counter in nanoseconds: it never goes backwards and it
// moves at the counter's rate.
static void frame_time(void) {
    int64_t first = dxc_native_frame_time_nanos();
    int64_t second = dxc_native_frame_time_nanos();
    CHECK(first > 0);
    CHECK(second >= first);
}

// The X11 calls the native image carries here answer that they have nothing.
static void x11_names(void) {
    int32_t x = 7, y = 7, width = 7, height = 7;
    char text[4] = "abc";
    dxc_native_window_origin(&x, &y);
    dxc_native_window_server_size(&width, &height);
    CHECK(x == 0 && y == 0 && width == 0 && height == 0);
    CHECK(dxc_native_window_focused() == 0);
    CHECK(dxc_native_ime_reset(text, sizeof text) == 0 && text[0] == 0);
    CHECK(dxc_native_resize_serial() == 0);
}

// The caption hit test at 100%, 150% and 200%: maximise answers as the maximise button so
// Snap Layouts shows, minimise and close stay client area, the rest drags the window.
static void caption_zones(void) {
    const int32_t dpis[] = {96, 144, 192};
    for (int i = 0; i < 3; i++) {
        int32_t dpi = dpis[i];
        int32_t button = dxc_caption_scale(46, dpi);
        int32_t height = dxc_caption_scale(32, dpi);
        int32_t width = 1200;
        int32_t mid = height / 2;
        CHECK(button == 46 * dpi / 96 && height == 32 * dpi / 96);
        CHECK(dxc_caption_zone_at(width - button / 2, mid, width, dpi, 8, 1, 0) == DXC_CAPTION_ZONE_CLOSE);
        CHECK(dxc_caption_zone_at(width - button * 3 / 2, mid, width, dpi, 8, 1, 0) == DXC_CAPTION_ZONE_MAXIMISE);
        CHECK(dxc_caption_zone_at(width - button * 5 / 2, mid, width, dpi, 8, 1, 0) == DXC_CAPTION_ZONE_MINIMISE);
        CHECK(dxc_caption_zone_at(width - button * 3 - 1, mid, width, dpi, 8, 1, 0) == DXC_CAPTION_ZONE_CAPTION);
        CHECK(dxc_caption_zone_at(width / 2, mid, width, dpi, 8, 1, 0) == DXC_CAPTION_ZONE_CAPTION);
        CHECK(dxc_caption_zone_at(width - button * 3 / 2, height, width, dpi, 8, 1, 0) == DXC_CAPTION_ZONE_CLIENT);
        CHECK(dxc_caption_zone_at(width / 2, 2, width, dpi, 8, 1, 0) == DXC_CAPTION_ZONE_TOP);
        // Maximised or fixed size: no top band, the buttons reach the top edge.
        CHECK(dxc_caption_zone_at(width - button * 3 / 2, 2, width, dpi, 8, 1, 1) == DXC_CAPTION_ZONE_MAXIMISE);
        CHECK(dxc_caption_zone_at(width / 2, 2, width, dpi, 8, 0, 0) == DXC_CAPTION_ZONE_CAPTION);
    }
}

int main(int argc, char **argv) {
    resize_decisions();
    wheel_direction();
    utf8_conversion();
    caption_zones();
    frame_time();
    x11_names();
    // Never true. It only makes the linker resolve the window and the toasts, so a symbol
    // the header declares and the objects lack fails the link.
    if (argc > 1000) {
        struct dxc_native_window window;
        dxc_native_window_open(argv[0], 1, 1, &window);
        dxc_native_frame_begin(window.swapchain, NULL);
        dxc_native_poll_event(NULL);
        dxc_native_clipboard_write(argv[0]);
        dxc_native_set_raster_resize(1);
        (void)dxc_native_window_is_zoomed();
        void *pixels = NULL;
        int32_t row_bytes = 0, width = 0, height = 0;
        if (dxc_native_frame_mode() != 0 &&
            dxc_native_raster_begin(&pixels, &row_bytes, &width, &height) == 0) {
            dxc_native_raster_end();
        }
        dxc_native_debug_resize(window.window, NULL, 1, 1, 2, 2, 1, 0);
        dxc_native_debug_hit_test(window.window);
        dxc_native_report_metrics(argv[0]);
        dxc_native_context_menu(window.window, argv[0]);
        dxc_native_window_chrome(0, 0, 0, 0);
        (void)dxc_native_frame_time_nanos();
        dxc_native_wake();
        dxc_notify_start();
    }
    if (failures == 0) printf("ok\n");
    return failures == 0 ? 0 : 1;
}
