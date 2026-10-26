// Checks the parts of the shared Win32 window that are arithmetic, that the objects the
// window is made of resolve every symbol the header declares, and that the records have
// the sizes the Kotlin/Native reader (src/.../Win32Records.kt) assumes. Compiled and run by the
// extended-window-windows-shared workflow with MSVC.
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <windows.h>

#include "win32_resize.h"
#include "win32_ime_text.h"
#include "win32_wheel.h"
#include "win32_caption.h"
#include "win32_multi.h"
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

// ---- Several windows in one process (FR-53) -------------------------------------------------

// The table finds the state of a window from its handle, and only that window's.
static void fr53_window_table_finds_state_by_handle(void) {
    struct dxc_window_table table;
    memset(&table, 0, sizeof table);
    int first_state = 1, second_state = 2;
    void *first = (void *)(uintptr_t)0x1000;
    void *second = (void *)(uintptr_t)0x2000;
    CHECK(dxc_table_find(&table, first) == NULL);
    CHECK(dxc_table_add(&table, first, &first_state) == 0);
    CHECK(dxc_table_add(&table, second, &second_state) == 0);
    CHECK(dxc_table_add(&table, first, &second_state) == 1);
    CHECK(table.count == 2);
    CHECK(dxc_table_find(&table, first) == &first_state);
    CHECK(dxc_table_find(&table, second) == &second_state);
    CHECK(dxc_table_handle_of(&table, &second_state) == second);
    CHECK(dxc_table_remove(&table, first) == &first_state);
    CHECK(dxc_table_find(&table, first) == NULL);
    CHECK(dxc_table_find(&table, second) == &second_state);
    CHECK(table.count == 1);
    CHECK(dxc_table_remove(&table, first) == NULL);
    for (uintptr_t index = 1; index <= DXC_MAX_WINDOWS; index++) {
        dxc_table_add(&table, (void *)(index * 16), &first_state);
    }
    CHECK(table.count == DXC_MAX_WINDOWS);
    CHECK(dxc_table_add(&table, (void *)(uintptr_t)0x99990, &first_state) == 1);
}

// The first window sets the process-wide state up and the last one takes it down.
static void fr53_shared_state_is_counted_by_windows(void) {
    struct dxc_shared shared;
    memset(&shared, 0, sizeof shared);
    CHECK(dxc_shared_release(&shared) == 0);
    CHECK(dxc_shared_acquire(&shared) == 1);
    CHECK(dxc_shared_acquire(&shared) == 0);
    CHECK(dxc_shared_release(&shared) == 0);
    CHECK(dxc_shared_release(&shared) == 1);
    CHECK(dxc_shared_acquire(&shared) == 1);
}

// Points to pixels and back, at the scales Windows offers, for positions either side of zero.
static void fr53_points_round_trip_at_every_scale(void) {
    const uint32_t dpis[] = {96, 120, 144, 168, 192, 288};
    const int32_t values[] = {0, 1, 7, 100, 333, 1919, -1, -7, -640, -1920};
    for (int d = 0; d < 6; d++) {
        for (int v = 0; v < 10; v++) {
            int32_t pixels = dxc_points_to_pixels(values[v], dpis[d]);
            CHECK(dxc_pixels_to_points(pixels, dpis[d]) == values[v]);
        }
    }
    CHECK(dxc_points_to_pixels(100, 96) == 100);
    CHECK(dxc_points_to_pixels(100, 144) == 150);
    CHECK(dxc_points_to_pixels(-100, 144) == -150);
    CHECK(dxc_points_to_pixels(1, 120) == 1);
    CHECK(dxc_points_to_pixels(3, 120) == 4);
    CHECK(dxc_points_to_pixels(-3, 120) == -4);
}

// Following the pointer: wherever the pointer is, the anchor point of the content is under it.
static void fr53_follow_pointer_keeps_anchor_under_pointer(void) {
    const uint32_t dpis[] = {96, 144, 192};
    for (int d = 0; d < 3; d++) {
        uint32_t dpi = dpis[d];
        int32_t pointers[] = {0, 17, 640, 1333, -250};
        for (int p = 0; p < 5; p++) {
            // The content starts 8 pixels inside the frame on the sides and 31 below the top.
            int32_t offset_x = 8, offset_y = 31;
            int32_t left = dxc_follow_origin(pointers[p], 24.0f, dpi, offset_x);
            int32_t top = dxc_follow_origin(pointers[p] + 9, 12.5f, dpi, offset_y);
            int32_t anchor_x_pixels = dxc_points_to_pixels(24, dpi);
            CHECK(left + offset_x + anchor_x_pixels == pointers[p]);
            int32_t anchor_y = top + offset_y;
            int32_t expected_anchor_y = (int32_t)(12.5f * (float)dpi / 96.0f + 0.5f);
            CHECK(anchor_y + expected_anchor_y == pointers[p] + 9);
        }
    }
    CHECK(dxc_follow_decide(1, 0) == DXC_FOLLOW_KEEP);
    CHECK(dxc_follow_decide(0, 0) == DXC_FOLLOW_RELEASED);
    CHECK(dxc_follow_decide(1, 1) == DXC_FOLLOW_CANCELLED);
    CHECK(dxc_follow_decide(0, 1) == DXC_FOLLOW_CANCELLED);
}

// One window pumps as it always has; with several, a second pump in the same turn does not wait.
static void fr53_second_pump_in_the_same_turn_does_not_wait(void) {
    CHECK(dxc_pump_waits(0, 1000, 999, 2000) == 1);
    CHECK(dxc_pump_waits(1, 1000, 999, 2000) == 1);
    CHECK(dxc_pump_waits(2, 1000, 999, 2000) == 0);
    CHECK(dxc_pump_waits(2, 10000, 999, 2000) == 1);
}

static int real_windows_ok;

static int open_test_window(struct dxc_native_window *window, const char *title) {
    return dxc_native_window_open(title, 320, 240, window) == 0;
}

static void pump_all(void) {
    dxc_native_pump(0.0);
    dxc_native_pump(0.0);
}

// Two real windows, on the software Direct3D 12 adapter if the machine has no other. Where the
// runner cannot make a window at all, the checks that need one say so and are skipped.
static void fr53_two_windows_have_independent_state(void) {
    struct dxc_native_window a, b;
    memset(&a, 0, sizeof a);
    memset(&b, 0, sizeof b);
    if (!open_test_window(&a, "a")) {
        printf("skip: fr53 real windows (the first window could not be made here)\n");
        return;
    }
    if (!open_test_window(&b, "b")) {
        printf("fail: fr53 the second window could not be made\n");
        failures++;
        return;
    }
    real_windows_ok = 1;
    CHECK(a.window != b.window);
    CHECK(dxc_native_window_count() == 2);

    dxc_native_window_select(a.window);
    dxc_native_window_set_position(120, 140);
    dxc_native_window_set_close_deferred(1);
    dxc_native_window_select(b.window);
    dxc_native_window_set_position(480, 320);

    int32_t x = 0, y = 0, width = 0, height = 0;
    dxc_native_window_select(a.window);
    CHECK(dxc_native_window_bounds(&x, &y, &width, &height) == 1);
    CHECK(x == 120 && y == 140);
    dxc_native_window_select(b.window);
    CHECK(dxc_native_window_bounds(&x, &y, &width, &height) == 1);
    CHECK(x == 480 && y == 320);

    // Each keeps its own events and its own size, set apart from the other's.
    dxc_native_window_select(a.window);
    dxc_native_window_set_content_size(300, 200);
    dxc_native_window_select(b.window);
    dxc_native_window_set_content_size(500, 360);
    pump_all();
    RECT a_client, b_client;
    CHECK(GetClientRect((HWND)a.window, &a_client));
    CHECK(GetClientRect((HWND)b.window, &b_client));
    CHECK(a_client.right != b_client.right && a_client.bottom != b_client.bottom);
    CHECK(a_client.right > 0 && b_client.right > a_client.right);
    dxc_native_window_destroy(a.window);
    dxc_native_window_destroy(b.window);
    pump_all();
    dxc_native_window_release(a.window);
    dxc_native_window_release(b.window);
}

static void fr53_window_position_round_trips(void) {
    if (!real_windows_ok) { printf("skip: fr53_window_position_round_trips\n"); return; }
    struct dxc_native_window c;
    memset(&c, 0, sizeof c);
    if (!open_test_window(&c, "c")) return;
    int32_t x = 0, y = 0, width = 0, height = 0;
    dxc_native_window_select(c.window);
    const int32_t positions[][2] = {{0, 0}, {37, 91}, {250, 40}, {-30, 60}};
    for (int index = 0; index < 4; index++) {
        dxc_native_window_set_position(positions[index][0], positions[index][1]);
        CHECK(dxc_native_window_bounds(&x, &y, &width, &height) == 1);
        CHECK(x == positions[index][0] && y == positions[index][1]);
    }
    // The content size comes back as the client area of the frame, in points.
    dxc_native_window_set_content_size(410, 270);
    RECT client;
    CHECK(GetClientRect((HWND)c.window, &client));
    UINT dpi = GetDpiForWindow((HWND)c.window);
    CHECK(dxc_pixels_to_points(client.right - client.left, dpi) == 410);
    CHECK(dxc_pixels_to_points(client.bottom - client.top, dpi) >= 270);
    dxc_native_window_destroy(c.window);
    pump_all();
    dxc_native_window_release(c.window);
}

// A close the system asks for reaches the window it was asked of, and no other.
static void fr53_close_request_reaches_only_its_window(void) {
    if (!real_windows_ok) { printf("skip: fr53_close_request_reaches_only_its_window\n"); return; }
    struct dxc_native_window a, b;
    memset(&a, 0, sizeof a);
    memset(&b, 0, sizeof b);
    if (!open_test_window(&a, "a2") || !open_test_window(&b, "b2")) return;
    dxc_native_window_select(a.window);
    dxc_native_window_set_close_deferred(1);
    dxc_native_window_select(b.window);
    dxc_native_window_set_close_deferred(1);
    PostMessageW((HWND)b.window, WM_CLOSE, 0, 0);
    pump_all();
    dxc_native_window_select(a.window);
    CHECK(dxc_native_take_close_request() == 0);
    dxc_native_window_select(b.window);
    CHECK(dxc_native_take_close_request() == 1);
    CHECK(dxc_native_take_close_request() == 0);
    // Asked, not closed: the window stays until the application closes it.
    CHECK(IsWindow((HWND)b.window));
    CHECK(IsWindow((HWND)a.window));
    dxc_native_window_destroy(a.window);
    dxc_native_window_destroy(b.window);
    pump_all();
    dxc_native_window_release(a.window);
    dxc_native_window_release(b.window);
}

// Destroying a window ends that window. Another stays, and the process is not asked to quit.
static void fr53_destroying_one_window_does_not_quit(void) {
    if (!real_windows_ok) { printf("skip: fr53_destroying_one_window_does_not_quit\n"); return; }
    struct dxc_native_window a, b;
    memset(&a, 0, sizeof a);
    memset(&b, 0, sizeof b);
    if (!open_test_window(&a, "a3") || !open_test_window(&b, "b3")) return;
    int32_t before = dxc_native_window_count();
    dxc_native_window_destroy(b.window);
    pump_all();
    CHECK(dxc_native_window_count() == before - 1);
    int32_t code = 0;
    CHECK(dxc_native_quit_posted(&code) == 0);
    CHECK(IsWindow((HWND)a.window));
    CHECK(!IsWindow((HWND)b.window));
    dxc_native_window_select(a.window);
    CHECK(dxc_native_window_closed() == 0);
    // Not closing is not the same as the application never quitting: it can ask, and hears.
    dxc_native_post_quit(7);
    pump_all();
    CHECK(dxc_native_quit_posted(&code) == 1 && code == 7);
    dxc_native_window_release(b.window);
}

// Activation is what the system said, once per change, per window.
static void fr53_activate_reports_focus(void) {
    if (!real_windows_ok) { printf("skip: fr53_activate_reports_focus\n"); return; }
    struct dxc_native_window a, b;
    memset(&a, 0, sizeof a);
    memset(&b, 0, sizeof b);
    if (!open_test_window(&a, "a4") || !open_test_window(&b, "b4")) return;
    int32_t active = -1;
    // Drain whatever opening the two said.
    dxc_native_window_select(a.window);
    dxc_native_take_activation(&active);
    dxc_native_window_select(b.window);
    dxc_native_take_activation(&active);
    SendMessageW((HWND)a.window, WM_ACTIVATE, WA_INACTIVE, 0);
    dxc_native_window_select(a.window);
    CHECK(dxc_native_take_activation(&active) == 1 && active == 0);
    CHECK(dxc_native_window_is_active() == 0);
    CHECK(dxc_native_take_activation(&active) == 0);
    dxc_native_window_select(b.window);
    CHECK(dxc_native_take_activation(&active) == 0);
    SendMessageW((HWND)a.window, WM_ACTIVATE, WA_ACTIVE, 0);
    dxc_native_window_select(a.window);
    CHECK(dxc_native_take_activation(&active) == 1 && active == 1);
    CHECK(dxc_native_window_is_active() == 1);
    dxc_native_window_destroy(a.window);
    dxc_native_window_destroy(b.window);
    pump_all();
    dxc_native_window_release(a.window);
    dxc_native_window_release(b.window);
}

int main(int argc, char **argv) {
    resize_decisions();
    wheel_direction();
    utf8_conversion();
    caption_zones();
    frame_time();
    x11_names();
    fr53_window_table_finds_state_by_handle();
    fr53_shared_state_is_counted_by_windows();
    fr53_points_round_trip_at_every_scale();
    fr53_follow_pointer_keeps_anchor_under_pointer();
    fr53_second_pump_in_the_same_turn_does_not_wait();
    // The software adapter, for a runner with no GPU.
    _putenv("DXC_D3D12_WARP=1");
    fr53_two_windows_have_independent_state();
    fr53_window_position_round_trips();
    fr53_close_request_reaches_only_its_window();
    fr53_destroying_one_window_does_not_quit();
    fr53_activate_reports_focus();
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
