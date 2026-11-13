// Checks the parts of the shared Win32 window that are arithmetic, that the objects the
// window is made of resolve every symbol the header declares, and that the records have
// the sizes the Kotlin/Native reader (src/.../Win32Records.kt) assumes. Compiled and run by the
// extended-window-windows-shared workflow with MSVC.
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <windows.h>
#include <wchar.h>

#include "win32_buttons.h"
#include "win32_resize.h"
#include "win32_ime_text.h"
#include "win32_wheel.h"
#include "win32_keys.h"
#include "win32_caption.h"
#include "win32_multi.h"
#include "win32_file_dialog_spec.h"
#include "win32_placement.h"
#include "win32_scheme.h"
#include "win32_edit_menu.h"
#include "win32_window.h"

// The records the Kotlin side reads and writes by offset. A field added on one side only is
// a reader that silently reads the wrong bytes, so the sizes are fixed here.
_Static_assert(sizeof(struct dxc_event) == 136, "struct dxc_event is 136 bytes");
_Static_assert(offsetof(struct dxc_event, text) == 28, "dxc_event text starts at 28");
_Static_assert(offsetof(struct dxc_event, time_millis) == 128, "dxc_event time_millis starts at 128");
_Static_assert(sizeof(struct dxc_element) == 116, "struct dxc_element is 116 bytes");
_Static_assert(offsetof(struct dxc_element, label) == 20, "dxc_element label starts at 20");
_Static_assert(sizeof(struct dxc_native_window) == 5 * sizeof(void *), "five pointers");

static int failures;

// The toasts ask the renderer for a frame; the renderer is not part of this check.
void compose_rust_renderer_request_frame(void) {}

#define CHECK(condition) \
    do { if (!(condition)) { printf("fail: %s (line %d)\n", #condition, __LINE__); failures++; } } while (0)

// The first window's outside against the work area of its monitor (#185).
static int placement_is(struct dxc_placement_rect r, int32_t l, int32_t t, int32_t rt, int32_t b) {
    return r.left == l && r.top == t && r.right == rt && r.bottom == b;
}

static void first_window_placement(void) {
    // A 1280x800 monitor whose taskbar leaves 1280x752.
    struct dxc_placement_rect work = {0, 0, 1280, 752};
    // Too big: shrunk to the work area and placed on it.
    CHECK(placement_is(dxc_placement_fit(work, 1616, 1039, 0, 0, 0, 0, 0), 0, 0, 1280, 752));
    // Too wide only: the height is kept and the window centred vertically.
    CHECK(placement_is(dxc_placement_fit(work, 1600, 600, 0, 0, 0, 0, 0), 0, 76, 1280, 676));
    // Fits: unchanged in size, centred as before.
    CHECK(placement_is(dxc_placement_fit(work, 800, 600, 0, 0, 0, 0, 0), 240, 76, 1040, 676));
    // Fits at an explicit position: left where it was asked for.
    CHECK(placement_is(dxc_placement_fit(work, 800, 600, 0, 0, 1, 100, 50), 100, 50, 900, 650));
    // Explicit position off the left and the top: pulled in so the caption is reachable.
    CHECK(placement_is(dxc_placement_fit(work, 800, 600, 0, 0, 1, -300, -40), 0, 0, 800, 600));
    // Off the right and the bottom: pulled back so the whole outside is on the monitor.
    CHECK(placement_is(dxc_placement_fit(work, 800, 600, 0, 0, 1, 1000, 700), 480, 152, 1280, 752));
    // Too big and positioned: shrunk, then placed at the work area's corner.
    CHECK(placement_is(dxc_placement_fit(work, 2000, 1200, 0, 0, 1, 300, 300), 0, 0, 1280, 752));
    // A minimum larger than the work area gives way to it; a smaller one still holds.
    CHECK(placement_is(dxc_placement_fit(work, 1600, 1000, 1400, 900, 0, 0, 0), 0, 0, 1280, 752));
    CHECK(placement_is(dxc_placement_fit(work, 200, 100, 400, 300, 0, 0, 0), 440, 226, 840, 526));

    // A secondary monitor left of and above the primary one, 1920x1040 of work area.
    struct dxc_placement_rect second = {-1920, -200, 0, 840};
    CHECK(placement_is(dxc_placement_fit(second, 2400, 1400, 0, 0, 0, 0, 0), -1920, -200, 0, 840));
    CHECK(placement_is(dxc_placement_fit(second, 800, 600, 0, 0, 0, 0, 0), -1360, 20, -560, 620));
    CHECK(placement_is(dxc_placement_fit(second, 800, 600, 0, 0, 1, -2500, -900), -1920, -200, -1120, 400));
    CHECK(placement_is(dxc_placement_fit(second, 800, 600, 0, 0, 1, -100, 600), -800, 240, 0, 840));

    // 150%: 1600x1000 points are 2400x1500 pixels plus the frame, on a monitor whose work
    // area is 2560x1392 pixels (1706x928 points).
    struct dxc_placement_rect scaled = {0, 0, 2560, 1392};
    CHECK(placement_is(dxc_placement_fit(scaled, 2422, 1556, 0, 0, 0, 0, 0), 69, 0, 2491, 1392));
    // 800x600 points at 150% fit and keep their size.
    CHECK(placement_is(dxc_placement_fit(scaled, 1222, 956, 0, 0, 0, 0, 0), 669, 218, 1891, 1174));
}

// A size given outside a drag is drawn before the window takes it (FR-19.8, compose-rust#684).
static void resize_predraw_decisions(void) {
    struct dxc_resize resize;
    memset(&resize, 0, sizeof resize);
    // Maximised from 624x472: drawn ahead, on the CPU.
    CHECK(dxc_resize_predraw_wanted(&resize, 1, 0, 1024, 728, 624, 472, 624, 472) == 1);
    // Not without CPU frames, not while a frame is being drawn, not for a minimised window.
    CHECK(dxc_resize_predraw_wanted(&resize, 0, 0, 1024, 728, 624, 472, 624, 472) == 0);
    CHECK(dxc_resize_predraw_wanted(&resize, 1, 1, 1024, 728, 624, 472, 624, 472) == 0);
    CHECK(dxc_resize_predraw_wanted(&resize, 1, 0, 0, 0, 624, 472, 624, 472) == 0);
    // A frame change that keeps the client size, or a size already on screen: nothing to draw.
    CHECK(dxc_resize_predraw_wanted(&resize, 1, 0, 624, 472, 624, 472, 624, 472) == 0);
    CHECK(dxc_resize_predraw_wanted(&resize, 1, 0, 496, 720, 624, 472, 496, 720) == 0);
    // Inside a drag WM_SIZE draws each step.
    dxc_resize_begin_drag(&resize);
    CHECK(dxc_resize_predraw_wanted(&resize, 1, 0, 1024, 728, 624, 472, 624, 472) == 0);
    dxc_resize_end_drag(&resize);

    struct dxc_predrawn predrawn;
    memset(&predrawn, 0, sizeof predrawn);
    CHECK(dxc_predrawn_take(&predrawn, 1024, 728) == 0);
    dxc_predrawn_set(&predrawn, 1024, 728);
    CHECK(dxc_predrawn_take(&predrawn, 1024, 728) == 1);
    // Shown once.
    CHECK(dxc_predrawn_take(&predrawn, 1024, 728) == 0);
    // Drawn for a size the window did not get: dropped, and the size is drawn as usual.
    dxc_predrawn_set(&predrawn, 1024, 728);
    CHECK(dxc_predrawn_take(&predrawn, 1008, 728) == 0);
    CHECK(dxc_predrawn_take(&predrawn, 1024, 728) == 0);
}

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

    // A refused refit asks again for the size, a few frames in a row, then waits for the next.
    struct dxc_resize refused;
    memset(&refused, 0, sizeof refused);
    dxc_resize_fitted(&refused, 800, 600);
    dxc_resize_note(&refused, 1024, 768);
    for (int i = 0; i < DXC_RESIZE_RETRIES; i++) {
        CHECK(dxc_resize_take(&refused, &width, &height) == 1);
        CHECK(width == 1024 && height == 768);
        CHECK(dxc_resize_refused(&refused, width, height) == 1);
    }
    CHECK(dxc_resize_take(&refused, &width, &height) == 1);
    CHECK(dxc_resize_refused(&refused, width, height) == 0);
    CHECK(dxc_resize_take(&refused, &width, &height) == 0);
    CHECK(refused.fitted_width == 800 && refused.fitted_height == 600);
    // A new size starts the count again, and a refit that succeeds ends it.
    dxc_resize_note(&refused, 1280, 720);
    CHECK(dxc_resize_take(&refused, &width, &height) == 1);
    CHECK(dxc_resize_refused(&refused, width, height) == 1);
    CHECK(dxc_resize_take(&refused, &width, &height) == 1);
    dxc_resize_fitted(&refused, width, height);
    CHECK(refused.refusals == 0);
    CHECK(dxc_resize_take(&refused, &width, &height) == 0);
    // A size given while the refused one was waiting is the one asked for next.
    dxc_resize_note(&refused, 640, 480);
    CHECK(dxc_resize_take(&refused, &width, &height) == 1);
    dxc_resize_note(&refused, 700, 500);
    CHECK(dxc_resize_refused(&refused, 640, 480) == 1);
    CHECK(dxc_resize_take(&refused, &width, &height) == 1);
    CHECK(width == 700 && height == 500);

    dxc_resize_begin_drag(&resize);
    CHECK(dxc_resize_draw_here(&resize) == 1);
    dxc_resize_end_drag(&resize);
    CHECK(dxc_resize_draw_here(&resize) == 0);
}

// The values Kotlin's WindowEvent names, and a right-button press carrying its own flag.
static void pointer_buttons(void) {
    CHECK(DXC_PRIMARY_HELD == 1 && DXC_SECONDARY_HELD == 2 && DXC_MIDDLE_HELD == 4);
    CHECK(DXC_BACK_HELD == 8 && DXC_FORWARD_HELD == 16);
    CHECK(DXC_SECONDARY_BUTTON == (1 << 16) && DXC_MIDDLE_BUTTON == (1 << 17));
    CHECK(DXC_BACK_BUTTON == (1 << 18) && DXC_FORWARD_BUTTON == (1 << 19));
    CHECK(dxc_pointer_buttons(DXC_SECONDARY_HELD, DXC_SECONDARY_BUTTON) == (DXC_SECONDARY_HELD | DXC_SECONDARY_BUTTON));
    CHECK(dxc_pointer_buttons(DXC_PRIMARY_HELD, DXC_SECONDARY_BUTTON) == (DXC_PRIMARY_HELD | DXC_SECONDARY_BUTTON));
    CHECK(dxc_pointer_buttons(DXC_MIDDLE_HELD, DXC_MIDDLE_BUTTON) == (DXC_MIDDLE_HELD | DXC_MIDDLE_BUTTON));
    CHECK(dxc_pointer_buttons(0, DXC_BACK_BUTTON) == DXC_BACK_BUTTON);
    CHECK(dxc_pointer_buttons(DXC_PRIMARY_HELD, 0) == DXC_PRIMARY_HELD);
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
    // Not one of them any more, but with no window and nothing composed it answers the same.
    CHECK(dxc_native_ime_reset(text, sizeof text) == 0 && text[0] == 0);
    CHECK(dxc_native_ime_reset(NULL, 0) == 0);
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

// What the scene hears while the pointer crosses between the maximise button (non-client)
// and the buttons beside it (client area): every hover ends, none ends twice, none ends
// while a button is held (#171).
static void caption_pointer_crossings(void) {
    struct dxc_caption_pointer pointer = {0};
    // Onto the maximise button and along it.
    CHECK(dxc_caption_nc_move(&pointer, 1) == DXC_CAPTION_FORWARD_MOVE);
    CHECK(dxc_caption_nc_move(&pointer, 1) == DXC_CAPTION_FORWARD_MOVE);
    // Up onto the top band or left onto the caption: Windows sends no leave, the scene is told.
    CHECK(dxc_caption_nc_move(&pointer, 0) == DXC_CAPTION_FORWARD_LEAVE);
    // Further along the caption says nothing more, and neither does leaving the window from there.
    CHECK(dxc_caption_nc_move(&pointer, 0) == DXC_CAPTION_FORWARD_NONE);
    CHECK(dxc_caption_nc_leave(&pointer, 0) == DXC_CAPTION_FORWARD_NONE);

    // Off the maximise button and out of the window.
    CHECK(dxc_caption_nc_move(&pointer, 1) == DXC_CAPTION_FORWARD_MOVE);
    CHECK(dxc_caption_nc_leave(&pointer, 0) == DXC_CAPTION_FORWARD_LEAVE);
    CHECK(dxc_caption_nc_leave(&pointer, 0) == DXC_CAPTION_FORWARD_NONE);

    // Maximise to close, the client move first: the late non-client leave must not end the
    // hover the close button has just been given.
    CHECK(dxc_caption_nc_move(&pointer, 1) == DXC_CAPTION_FORWARD_MOVE);
    dxc_caption_client_move(&pointer);
    CHECK(dxc_caption_nc_leave(&pointer, 0) == DXC_CAPTION_FORWARD_NONE);
    // Close to maximise, the non-client move first: the late client leave is not passed on.
    CHECK(dxc_caption_nc_move(&pointer, 1) == DXC_CAPTION_FORWARD_MOVE);
    CHECK(dxc_caption_client_leave(&pointer, 0) == DXC_CAPTION_FORWARD_NONE);
    CHECK(pointer.over_maximise == 1);
    // The same crossings with the leave first: a leave, then the move that follows it.
    CHECK(dxc_caption_nc_leave(&pointer, 0) == DXC_CAPTION_FORWARD_LEAVE);
    dxc_caption_client_move(&pointer);
    CHECK(dxc_caption_client_leave(&pointer, 0) == DXC_CAPTION_FORWARD_LEAVE);
    CHECK(dxc_caption_nc_move(&pointer, 1) == DXC_CAPTION_FORWARD_MOVE);

    // Pressed on the maximise button: the mouse is captured, Windows says the pointer left the
    // non-client area, and the scene keeps its press.
    CHECK(dxc_caption_nc_leave(&pointer, 1) == DXC_CAPTION_FORWARD_NONE);
    CHECK(pointer.over_maximise == 0);
    // A drag that leaves the window with a button down is not the end of the drag.
    dxc_caption_client_move(&pointer);
    CHECK(dxc_caption_client_leave(&pointer, 1) == DXC_CAPTION_FORWARD_NONE);
    // Off the close button and out of the window with nothing held.
    CHECK(dxc_caption_client_leave(&pointer, 0) == DXC_CAPTION_FORWARD_LEAVE);
    // The point of a leave is outside every window, whatever its size.
    CHECK(DXC_CAPTION_OUTSIDE < 0);
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

// A press is dated by when the system posted it (GetMessageTime, read unsigned), and the
// double-click interval is the one set in the mouse settings.
static void pointer_events_carry_the_message_time(void) {
    CHECK(dxc_native_double_click_millis() == (int64_t)GetDoubleClickTime());
    if (!real_windows_ok) { printf("skip: pointer_events_carry_the_message_time\n"); return; }
    struct dxc_native_window d;
    memset(&d, 0, sizeof d);
    if (!open_test_window(&d, "d")) return;
    dxc_native_window_select(d.window);
    struct dxc_event event;
    pump_all();
    while (dxc_native_poll_event(&event)) {}
    DWORD before = GetTickCount();
    PostMessageW((HWND)d.window, WM_LBUTTONDOWN, MK_LBUTTON, MAKELPARAM(10, 10));
    PostMessageW((HWND)d.window, WM_LBUTTONUP, 0, MAKELPARAM(10, 10));
    pump_all();
    int64_t pressed = 0;
    while (dxc_native_poll_event(&event)) {
        if (event.kind == DXC_EVENT_POINTER_DOWN) pressed = event.time_millis;
    }
    CHECK(pressed != 0);
    // Within ten seconds of the tick count when it was posted, on the same unsigned clock.
    CHECK((uint32_t)((uint32_t)pressed - (uint32_t)before) < 10000u ||
          (uint32_t)((uint32_t)before - (uint32_t)pressed) < 10000u);
    dxc_native_window_destroy(d.window);
    pump_all();
    dxc_native_window_release(d.window);
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

// The type list a dialog is given: one line per filter, a label, a tab and the extensions.
static void fr72_filter_lines_become_dialog_patterns(void) {
    const char *filters = "Images\tpng,jpg\n\nAll files\nDocuments\t\nOdd\t,a,,b,";
    const char *label, *extensions, *first;
    size_t label_length, extensions_length;
    char pattern[64];
    CHECK(dxc_file_filter_count(filters) == 4);
    CHECK(dxc_file_filter_count("") == 0);
    CHECK(dxc_file_filter_count(NULL) == 0);
    CHECK(dxc_file_filter_at(filters, 0, &label, &label_length, &extensions, &extensions_length) == 1);
    CHECK(label_length == 6 && memcmp(label, "Images", 6) == 0);
    CHECK(dxc_file_filter_pattern(extensions, extensions_length, NULL, 0) == 11);
    CHECK(dxc_file_filter_pattern(extensions, extensions_length, pattern, sizeof pattern) == 11);
    CHECK(strcmp(pattern, "*.png;*.jpg") == 0);
    // A buffer too small is filled and ended, and the whole length is still reported.
    CHECK(dxc_file_filter_pattern(extensions, extensions_length, pattern, 5) == 11);
    CHECK(strcmp(pattern, "*.pn") == 0);
    CHECK(dxc_file_filter_first_extension(extensions, extensions_length, &first) == 3);
    CHECK(memcmp(first, "png", 3) == 0);
    // No tab, and a tab with nothing after it, are both every file.
    CHECK(dxc_file_filter_at(filters, 1, &label, &label_length, &extensions, &extensions_length) == 1);
    CHECK(label_length == 9 && extensions_length == 0);
    CHECK(dxc_file_filter_pattern(extensions, extensions_length, pattern, sizeof pattern) == 3);
    CHECK(strcmp(pattern, "*.*") == 0);
    CHECK(dxc_file_filter_first_extension(extensions, extensions_length, &first) == 0);
    CHECK(dxc_file_filter_at(filters, 2, &label, &label_length, &extensions, &extensions_length) == 1);
    CHECK(label_length == 9 && extensions_length == 0);
    // Empty entries are skipped.
    CHECK(dxc_file_filter_at(filters, 3, &label, &label_length, &extensions, &extensions_length) == 1);
    CHECK(dxc_file_filter_pattern(extensions, extensions_length, pattern, sizeof pattern) == 7);
    CHECK(strcmp(pattern, "*.a;*.b") == 0);
    CHECK(dxc_file_filter_first_extension(extensions, extensions_length, &first) == 1 && *first == 'a');
    CHECK(dxc_file_filter_at(filters, 4, &label, &label_length, &extensions, &extensions_length) == 0);
}

// What cannot be a dialog starts nothing and leaves no answer behind.
static void fr72_a_dialog_that_cannot_start_answers_nothing(void) {
    int64_t request = 0;
    int32_t status = 0, filter = 0, count = 0, bytes = 0;
    CHECK(dxc_native_file_dialog_show(NULL, 1, DXC_FILE_DIALOG_OPEN_FILE, "", "", "", "", 0) != 0);
    CHECK(dxc_native_file_dialog_show((void *)(intptr_t)0x7ffffff0, 2, DXC_FILE_DIALOG_OPEN_FILE, "", "", "", "", 0) != 0);
    CHECK(dxc_native_file_dialog_next(&request, &status, &filter, &count, &bytes) == 0);
    CHECK(dxc_native_file_dialog_take(1, NULL, 0) == -1);
    // Nothing is open under this number, so this is a call that does nothing.
    dxc_native_file_dialog_cancel(1);
}

// Pumps this thread until the oldest answer is there, for at most [millis].
static int wait_for_dialog_answer(int64_t *request, int32_t *status, int32_t *filter, int32_t *count,
                                  int32_t *bytes, DWORD millis) {
    DWORD start = GetTickCount();
    while (GetTickCount() - start < millis) {
        if (dxc_native_file_dialog_next(request, status, filter, count, bytes)) return 1;
        dxc_native_pump(0.02);
    }
    return 0;
}

// A dialog on a real window: the window's thread is not held while the dialog is open, a
// second dialog under the same number is refused, and a dialog told to close answers once,
// as cancelled. A runner that cannot show a dialog answers unavailable, also once.
static void fr72_an_open_dialog_does_not_hold_the_loop_and_answers_once(void) {
    if (!real_windows_ok) { printf("skip: fr72_an_open_dialog_does_not_hold_the_loop_and_answers_once\n"); return; }
    struct dxc_native_window a;
    memset(&a, 0, sizeof a);
    if (!open_test_window(&a, "dialog")) return;
    int64_t request = 0;
    int32_t status = 0, filter = 0, count = 0, bytes = 0;
    DWORD before = GetTickCount();
    CHECK(dxc_native_file_dialog_show(a.window, 41, DXC_FILE_DIALOG_OPEN_FILES, "Pick", "", "",
                                      "Text\ttxt,md\nAll files", 0) == 0);
    // Show came back without waiting for the reader.
    CHECK(GetTickCount() - before < 2000);
    CHECK(dxc_native_file_dialog_show(a.window, 41, DXC_FILE_DIALOG_SAVE, "", "", "", "", 0) != 0);
    CHECK(dxc_native_file_dialog_show(a.window, 43, 0, "", "", "", "", 0) != 0);
    CHECK(dxc_native_file_dialog_show(a.window, 43, 6, "", "", "", "", 0) != 0);
    CHECK(dxc_native_file_dialog_show(a.window, 43, DXC_FILE_DIALOG_SAVE, "\xff", "", "", "", 0) != 0);
    // The loop turns while the dialog is opening and open.
    for (int turn = 0; turn < 25; turn++) dxc_native_pump(0.02);
    dxc_native_file_dialog_cancel(41);
    CHECK(wait_for_dialog_answer(&request, &status, &filter, &count, &bytes, 20000) == 1);
    CHECK(request == 41);
    CHECK(status == DXC_FILE_DIALOG_CANCELLED || status == DXC_FILE_DIALOG_UNAVAILABLE);
    CHECK(count == 0 && bytes == 0 && filter == -1);
    printf("fr72 dialog answered %s\n", status == DXC_FILE_DIALOG_CANCELLED ? "cancelled" : "unavailable");
    CHECK(dxc_native_file_dialog_take(41, NULL, 0) == 0);
    // Taken once: there is no second answer.
    CHECK(dxc_native_file_dialog_take(41, NULL, 0) == -1);
    for (int turn = 0; turn < 10; turn++) dxc_native_pump(0.02);
    CHECK(dxc_native_file_dialog_next(&request, &status, &filter, &count, &bytes) == 0);
    // The owner takes input again once its dialog is gone.
    CHECK(IsWindowEnabled((HWND)a.window));

    // A dialog whose owner is destroyed closes by itself and still answers, once.
    CHECK(dxc_native_file_dialog_show(a.window, 42, DXC_FILE_DIALOG_OPEN_FOLDER, "", "", "", "", 0) == 0);
    for (int turn = 0; turn < 25; turn++) dxc_native_pump(0.02);
    dxc_native_window_destroy(a.window);
    CHECK(wait_for_dialog_answer(&request, &status, &filter, &count, &bytes, 20000) == 1);
    CHECK(request == 42);
    CHECK(status == DXC_FILE_DIALOG_CANCELLED || status == DXC_FILE_DIALOG_UNAVAILABLE);
    CHECK(dxc_native_file_dialog_take(42, NULL, 0) == 0);
    pump_all();
    dxc_native_window_release(a.window);
}

// ---- a dialog played through, with this thread as the reader ---------------------------------
//
// The runner has a desktop, so the dialog is real. What a reader would do with the keyboard
// is done here with messages to the dialog's own controls: the name goes into its name field
// and its default button is pressed. This thread goes on pumping throughout, which is the
// point: the dialog is on a thread of its own.

static HWND dialog_owner;

static BOOL CALLBACK find_owned_dialog(HWND window, LPARAM out) {
    WCHAR name[64];
    if (IsWindowVisible(window) && GetWindow(window, GW_OWNER) == dialog_owner &&
        GetClassNameW(window, name, 64) > 0 && wcscmp(name, L"#32770") == 0) {
        *(HWND *)out = window;
        return FALSE;
    }
    return TRUE;
}

static BOOL CALLBACK find_name_field(HWND window, LPARAM out) {
    WCHAR name[64];
    if (IsWindowVisible(window) && GetClassNameW(window, name, 64) > 0 && wcscmp(name, L"Edit") == 0) {
        *(HWND *)out = window;
        return FALSE;
    }
    return TRUE;
}

static HWND owned_dialog(HWND owner) {
    HWND dialog = NULL;
    dialog_owner = owner;
    EnumWindows(find_owned_dialog, (LPARAM)&dialog);
    return dialog;
}

struct played_dialog {
    int answered;
    int64_t request;
    int32_t status, filter, count, bytes;
    char paths[4096];
    // The loop of this thread while the dialog was open.
    int turns;
    DWORD longest_turn;
    // 1 once a second window (the system's own question) stood over the dialog.
    int asked;
};

// Pumps until the dialog of [request] answers. Once the dialog is up and has settled, types
// [text] into its name field and presses its default button, again every second until it
// answers ([type_once] types only before the first press). With [stop_when_asked], a question over the dialog (replace this file?) ends the
// play instead, by cancelling the dialog.
static void play_dialog_typing(HWND owner, int64_t request, const WCHAR *text, int stop_when_asked,
                               int type_once, struct played_dialog *played) {
    memset(played, 0, sizeof *played);
    DWORD start = GetTickCount(), last_turn = start, seen_at = 0, pressed_at = 0;
    int cancelled = 0;
    while (GetTickCount() - start < 30000) {
        dxc_native_pump(0.01);
        DWORD now = GetTickCount();
        if (now - last_turn > played->longest_turn) played->longest_turn = now - last_turn;
        last_turn = now;
        played->turns++;
        if (dxc_native_file_dialog_next(&played->request, &played->status, &played->filter,
                                        &played->count, &played->bytes)) {
            if (played->bytes < (int32_t)sizeof played->paths) {
                dxc_native_file_dialog_take(request, played->paths, (int32_t)sizeof played->paths - 1);
                played->answered = 1;
            }
            return;
        }
        HWND dialog = owned_dialog(owner);
        if (dialog == NULL || cancelled) continue;
        if (seen_at == 0) seen_at = now;
        HWND question = GetWindow(dialog, GW_ENABLEDPOPUP);
        if (question != NULL && question != dialog && !IsWindowEnabled(dialog)) {
            played->asked = 1;
            if (stop_when_asked) {
                dxc_native_file_dialog_cancel(request);
                cancelled = 1;
            }
            continue;
        }
        // A moment for the dialog to fill itself in, then once a second.
        if (now - seen_at < 1500 || (pressed_at != 0 && now - pressed_at < 1000)) continue;
        HWND field = NULL;
        EnumChildWindows(dialog, find_name_field, (LPARAM)&field);
        if (field == NULL) continue;
        DWORD_PTR ignored = 0;
        // A folder picker opens the folder named on the first press and chooses the folder it
        // is in on the next, so there the name is typed once. No text is the name the dialog
        // came up with, accepted as it stands.
        if (text != NULL && (pressed_at == 0 || !type_once)) {
            SendMessageTimeoutW(field, WM_SETTEXT, 0, (LPARAM)text, SMTO_ABORTIFHUNG, 2000, &ignored);
        }
        PostMessageW(dialog, WM_COMMAND, MAKEWPARAM(IDOK, BN_CLICKED), (LPARAM)GetDlgItem(dialog, IDOK));
        pressed_at = now;
    }
    // Nobody answered: close it so the checks that follow start clean.
    dxc_native_file_dialog_cancel(request);
    DWORD wait = GetTickCount();
    while (GetTickCount() - wait < 10000) {
        dxc_native_pump(0.01);
        if (dxc_native_file_dialog_next(&played->request, &played->status, &played->filter,
                                        &played->count, &played->bytes)) {
            dxc_native_file_dialog_take(played->request, played->paths, (int32_t)sizeof played->paths - 1);
            break;
        }
    }
    played->answered = 0;
}

static void play_dialog(HWND owner, int64_t request, const WCHAR *text, int stop_when_asked,
                        struct played_dialog *played) {
    play_dialog_typing(owner, request, text, stop_when_asked, 0, played);
}

static void utf8_of(const WCHAR *wide, char *out, int capacity) {
    if (WideCharToMultiByte(CP_UTF8, 0, wide, -1, out, capacity, NULL, NULL) == 0) out[0] = '\0';
}

static void make_file(const WCHAR *directory, const WCHAR *name) {
    WCHAR path[MAX_PATH];
    swprintf(path, MAX_PATH, L"%ls\\%ls", directory, name);
    HANDLE file = CreateFileW(path, GENERIC_WRITE, 0, NULL, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    if (file != INVALID_HANDLE_VALUE) CloseHandle(file);
}

// The whole path of a dialog, for each kind: what the reader chose reaches the asking thread
// whole and in UTF-8, the loop of that thread never stops while the dialog is open, a save
// takes its extension from the selected type and asks before replacing a file.
static void fr72_dialogs_answer_what_the_reader_chose(void) {
    if (!real_windows_ok) { printf("skip: fr72_dialogs_answer_what_the_reader_chose\n"); return; }
    WCHAR temp[MAX_PATH], directory[MAX_PATH], sub[MAX_PATH];
    GetTempPathW(MAX_PATH, temp);
    // The long form: the dialog answers with it, whatever form it was given.
    WCHAR long_temp[MAX_PATH];
    if (GetLongPathNameW(temp, long_temp, MAX_PATH) == 0) wcscpy(long_temp, temp);
    swprintf(directory, MAX_PATH, L"%lsdxc-dialog-%lu", long_temp, GetCurrentProcessId());
    CreateDirectoryW(directory, NULL);
    swprintf(sub, MAX_PATH, L"%ls\\sub one", directory);
    CreateDirectoryW(sub, NULL);
    make_file(directory, L"alpha.txt");
    make_file(directory, L"\xd55c\xae00 \xd30c\xc77c.txt");
    char directory_utf8[1024], expected[2048];
    utf8_of(directory, directory_utf8, sizeof directory_utf8);

    struct dxc_native_window a;
    memset(&a, 0, sizeof a);
    if (!open_test_window(&a, "dialogs")) return;
    const char *filters = "Text\ttxt,md\nAll files";
    struct played_dialog played;

    // One file.
    CHECK(dxc_native_file_dialog_show(a.window, 51, DXC_FILE_DIALOG_OPEN_FILE, "One file", directory_utf8, "",
                                      filters, 0) == 0);
    play_dialog((HWND)a.window, 51, L"alpha.txt", 0, &played);
    if (played.status == DXC_FILE_DIALOG_UNAVAILABLE) {
        // A runner with no shell to show the dialog. Said, so the log shows what was not checked.
        printf("skip: fr72_dialogs_answer_what_the_reader_chose (the dialog is unavailable here)\n");
        dxc_native_window_destroy(a.window);
        pump_all();
        dxc_native_window_release(a.window);
        return;
    }
    CHECK(played.answered == 1 && played.request == 51);
    CHECK(played.status == DXC_FILE_DIALOG_CHOSEN && played.count == 1 && played.filter == 0);
    snprintf(expected, sizeof expected, "%s\\alpha.txt", directory_utf8);
    CHECK(played.bytes == (int32_t)strlen(expected) && _stricmp(played.paths, expected) == 0);
    // The dialog was open for more than a second and this thread kept turning all the while.
    CHECK(played.turns > 20);
    CHECK(played.longest_turn < 1000);
    printf("fr72 open file: %d loop turns while the dialog was open, longest %lu ms, answer %s\n",
           played.turns, played.longest_turn, played.paths);
    CHECK(IsWindowEnabled((HWND)a.window));

    // Several files, one of them with a name outside ASCII: both whole, a NUL between.
    CHECK(dxc_native_file_dialog_show(a.window, 52, DXC_FILE_DIALOG_OPEN_FILES, "", directory_utf8, "",
                                      "All files", 0) == 0);
    play_dialog((HWND)a.window, 52, L"\"alpha.txt\" \"\xd55c\xae00 \xd30c\xc77c.txt\"", 0, &played);
    CHECK(played.answered == 1 && played.status == DXC_FILE_DIALOG_CHOSEN && played.count == 2);
    {
        char korean[1024], second_expected[2048];
        utf8_of(L"\xd55c\xae00 \xd30c\xc77c.txt", korean, sizeof korean);
        snprintf(second_expected, sizeof second_expected, "%s\\%s", directory_utf8, korean);
        const char *first = played.paths;
        const char *second = first + strlen(first) + 1;
        CHECK(played.bytes == (int32_t)(strlen(first) + 1 + strlen(second)));
        // The dialog chooses the order; both are there.
        CHECK((_stricmp(first, expected) == 0 && strcmp(second, second_expected) == 0) ||
              (_stricmp(second, expected) == 0 && strcmp(first, second_expected) == 0));
    }

    // A folder. The picker opens the folder named on the first press and chooses it on the next.
    CHECK(dxc_native_file_dialog_show(a.window, 53, DXC_FILE_DIALOG_OPEN_FOLDER, "", directory_utf8, "",
                                      filters, 0) == 0);
    play_dialog_typing((HWND)a.window, 53, sub, 0, 1, &played);
    snprintf(expected, sizeof expected, "%s\\sub one", directory_utf8);
    printf("fr72 folder: answered=%d status=%d count=%d filter=%d paths=%s\n", played.answered, played.status,
           played.count, played.filter, played.paths);
    CHECK(played.answered == 1 && played.status == DXC_FILE_DIALOG_CHOSEN && played.count == 1);
    // A folder picker has no type list.
    CHECK(played.filter == -1);
    CHECK(_stricmp(played.paths, expected) == 0);

    // A save: the suggested name is in the name field, and accepted as it stands, without an
    // extension, it takes the selected type's first one.
    CHECK(dxc_native_file_dialog_show(a.window, 54, DXC_FILE_DIALOG_SAVE, "", directory_utf8, "report",
                                      "All files\nMarkdown\tmd,markdown", 1) == 0);
    play_dialog_typing((HWND)a.window, 54, NULL, 0, 0, &played);
    snprintf(expected, sizeof expected, "%s\\report.md", directory_utf8);
    printf("fr72 save: answered=%d status=%d count=%d filter=%d paths=%s\n", played.answered, played.status,
           played.count, played.filter, played.paths);
    CHECK(played.answered == 1 && played.status == DXC_FILE_DIALOG_CHOSEN && played.count == 1);
    CHECK(played.filter == 1);
    CHECK(_stricmp(played.paths, expected) == 0);
    // Only the path is answered: nothing was written.
    {
        WCHAR made[MAX_PATH];
        swprintf(made, MAX_PATH, L"%ls\\report.md", directory);
        CHECK(GetFileAttributesW(made) == INVALID_FILE_ATTRIBUTES);
    }

    // A save over a file that is there: the system asks first, and no answer comes until the
    // reader has said yes. Here nobody says yes; the dialog is told to close while the
    // question is up, and it closes, answers cancelled and gives the window back its input.
    _putenv("DXC_FILE_DIALOG_TRACE=1");
    CHECK(dxc_native_file_dialog_show(a.window, 55, DXC_FILE_DIALOG_SAVE, "", directory_utf8, "", filters, 0) == 0);
    play_dialog((HWND)a.window, 55, L"alpha.txt", 1, &played);
    printf("fr72 save over a file: asked=%d answered=%d status=%d count=%d owner_enabled=%d\n", played.asked,
           played.answered, played.status, played.count, IsWindowEnabled((HWND)a.window));
    CHECK(played.asked == 1);
    CHECK(played.answered == 1 && played.status == DXC_FILE_DIALOG_CANCELLED && played.count == 0);
    CHECK(IsWindowEnabled((HWND)a.window));
    CHECK(owned_dialog((HWND)a.window) == NULL);
    fflush(stdout);

    // Whatever is still open goes with its owner, and its answer is taken.
    dxc_native_window_destroy(a.window);
    {
        int64_t request = 0;
        int32_t status = 0, filter = 0, count = 0, bytes = 0;
        DWORD wait = GetTickCount();
        while (GetTickCount() - wait < 3000) {
            dxc_native_pump(0.01);
            if (dxc_native_file_dialog_next(&request, &status, &filter, &count, &bytes)) {
                dxc_native_file_dialog_take(request, played.paths, (int32_t)sizeof played.paths - 1);
            }
        }
    }
    pump_all();
    dxc_native_window_release(a.window);
}

// Changing an open window's resizable and color scheme (FR-53).
static void open_window_scheme(void) {
    uint32_t base = 0x10CF0000u; // WS_OVERLAPPEDWINDOW | WS_VISIBLE
    CHECK(dxc_scheme_style(base, 0) == (base & ~0x00050000u));
    CHECK(dxc_scheme_style(dxc_scheme_style(base, 0), 1) == base);
    CHECK(dxc_scheme_style(base, 1) == base);
    CHECK(dxc_scheme_valid(0) && dxc_scheme_valid(1) && dxc_scheme_valid(2));
    CHECK(!dxc_scheme_valid(3) && !dxc_scheme_valid(-1));
    CHECK(dxc_scheme_caption_dark(2, 0) == 1);
    CHECK(dxc_scheme_caption_dark(1, 1) == 0);
    CHECK(dxc_scheme_caption_dark(0, 1) == 1);
    CHECK(dxc_scheme_caption_dark(0, 0) == 0);
    static const uint16_t theme[] = {'I','m','m','e','r','s','i','v','e','C','o','l','o','r','S','e','t',0};
    static const uint16_t longer[] = {'I','m','m','e','r','s','i','v','e','C','o','l','o','r','S','e','t','s',0};
    static const uint16_t other[] = {'P','o','l','i','c','y',0};
    CHECK(dxc_scheme_is_theme_change(theme));
    CHECK(!dxc_scheme_is_theme_change(longer));
    CHECK(!dxc_scheme_is_theme_change(other));
    CHECK(!dxc_scheme_is_theme_change(NULL));
}

// F1 to F12, Shift with F10 and the menu key are the scene's, press and release, plain or
// as a system key, and never reach the default handler that would enter menu mode on F10 or
// put up its own context menu. Only Alt with F4 goes on to it, to close the window.
static void function_keys_reach_the_scene(void) {
    const UINT messages[] = {WM_KEYDOWN, WM_SYSKEYDOWN, WM_KEYUP, WM_SYSKEYUP};
    for (int index = 0; index < 4; index++) {
        UINT message = messages[index];
        int press = message == WM_KEYDOWN || message == WM_SYSKEYDOWN;
        CHECK(dxc_key_is_press(message) == press);
        for (WPARAM key = VK_F1; key <= VK_F12; key++) {
            int closes = message == WM_SYSKEYDOWN && key == VK_F4;
            CHECK(dxc_key_passes_to_system(message, key) == closes);
        }
        CHECK(!dxc_key_passes_to_system(message, VK_F10));
        CHECK(!dxc_key_passes_to_system(message, VK_APPS));
        CHECK(!dxc_key_passes_to_system(message, VK_MENU));
        CHECK(!dxc_key_passes_to_system(message, VK_SHIFT));
    }
    CHECK(VK_F10 == 0x79 && VK_APPS == 0x5D);
    CHECK(!dxc_key_is_press(WM_CHAR));
    CHECK(!dxc_key_is_press(WM_SYSCHAR));
}

// The window's own text edit menu (#142): which entries can be chosen, what a choice
// presses, and which keys ask for the menu.
static int entry_is(struct dxc_edit_entry entry, int32_t id, int32_t enabled, int32_t separator) {
    return entry.id == id && entry.enabled == enabled && entry.separator_before == separator;
}

static void edit_menu_entries(void) {
    struct dxc_edit_entry e[DXC_EDIT_ENTRY_COUNT];
    // Off: no menu at all, whatever else is said.
    CHECK(dxc_edit_menu_entries(0, 1, e) == 0);
    CHECK(dxc_edit_menu_entries(DXC_EDIT_MENU_KNOWN | DXC_EDIT_MENU_SELECTION, 1, e) == 0);
    // On with the field undescribed: everything but a Paste with nothing to paste.
    CHECK(dxc_edit_menu_entries(DXC_EDIT_MENU_ON, 0, e) == 4);
    CHECK(entry_is(e[0], DXC_EDIT_CUT, 1, 0) && entry_is(e[1], DXC_EDIT_COPY, 1, 0));
    CHECK(entry_is(e[2], DXC_EDIT_PASTE, 0, 0) && entry_is(e[3], DXC_EDIT_SELECT_ALL, 1, 1));
    CHECK(dxc_edit_menu_entries(DXC_EDIT_MENU_ON, 1, e) == 4 && e[2].enabled == 1);
    // An editable field with a selection: all four, Paste when the clipboard has text.
    int32_t editing = DXC_EDIT_MENU_ON | DXC_EDIT_MENU_KNOWN | DXC_EDIT_MENU_EDITABLE | DXC_EDIT_MENU_HAS_TEXT;
    dxc_edit_menu_entries(editing | DXC_EDIT_MENU_SELECTION, 1, e);
    CHECK(e[0].enabled && e[1].enabled && e[2].enabled && e[3].enabled);
    // Nothing selected: no Cut and no Copy.
    dxc_edit_menu_entries(editing, 1, e);
    CHECK(!e[0].enabled && !e[1].enabled && e[2].enabled && e[3].enabled);
    // Read only with a selection: Copy and Select All, never Cut or Paste.
    dxc_edit_menu_entries(DXC_EDIT_MENU_ON | DXC_EDIT_MENU_KNOWN | DXC_EDIT_MENU_SELECTION | DXC_EDIT_MENU_HAS_TEXT, 1, e);
    CHECK(!e[0].enabled && e[1].enabled && !e[2].enabled && e[3].enabled);
    // An empty field: only Paste, and only with something to paste.
    dxc_edit_menu_entries(DXC_EDIT_MENU_ON | DXC_EDIT_MENU_KNOWN | DXC_EDIT_MENU_EDITABLE, 0, e);
    CHECK(!e[0].enabled && !e[1].enabled && !e[2].enabled && !e[3].enabled);

    // A choice presses Control and the entry's letter; a dismissed menu (0) presses nothing.
    CHECK(dxc_edit_menu_key(DXC_EDIT_CUT) == 'X' && dxc_edit_menu_key(DXC_EDIT_COPY) == 'C');
    CHECK(dxc_edit_menu_key(DXC_EDIT_PASTE) == 'V' && dxc_edit_menu_key(DXC_EDIT_SELECT_ALL) == 'A');
    CHECK(dxc_edit_menu_key(0) == 0 && dxc_edit_menu_key(5) == 0);

    // The Menu key, alone or with Shift, and Shift and F10; nothing else.
    CHECK(dxc_edit_menu_key_opens(VK_APPS, 0) && dxc_edit_menu_key_opens(VK_APPS, 1));
    CHECK(dxc_edit_menu_key_opens(VK_F10, 1));
    CHECK(!dxc_edit_menu_key_opens(VK_F10, 0) && !dxc_edit_menu_key_opens(VK_F10, 1 | 2));
    CHECK(!dxc_edit_menu_key_opens(VK_APPS, 2) && !dxc_edit_menu_key_opens('C', 1));

    // The system's edit control names its entries by these commands, and an entry it lacks
    // still has a label.
    CHECK(dxc_edit_menu_system_command(DXC_EDIT_CUT) == WM_CUT);
    CHECK(dxc_edit_menu_system_command(DXC_EDIT_COPY) == WM_COPY);
    CHECK(dxc_edit_menu_system_command(DXC_EDIT_PASTE) == WM_PASTE);
    CHECK(dxc_edit_menu_system_command(DXC_EDIT_SELECT_ALL) == EM_SETSEL);
    CHECK(wcscmp(dxc_edit_menu_fallback_label(DXC_EDIT_SELECT_ALL), L"Select &All") == 0);
}

int main(int argc, char **argv) {
    resize_decisions();
    resize_predraw_decisions();
    function_keys_reach_the_scene();
    pointer_buttons();
    first_window_placement();
    open_window_scheme();
    wheel_direction();
    utf8_conversion();
    caption_zones();
    caption_pointer_crossings();
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
    pointer_events_carry_the_message_time();
    fr53_close_request_reaches_only_its_window();
    fr53_destroying_one_window_does_not_quit();
    fr53_activate_reports_focus();
    fr72_filter_lines_become_dialog_patterns();
    fr72_a_dialog_that_cannot_start_answers_nothing();
    fr72_an_open_dialog_does_not_hold_the_loop_and_answers_once();
    fr72_dialogs_answer_what_the_reader_chose();
    edit_menu_entries();
    // Never true. It only makes the linker resolve the window and the toasts, so a symbol
    // the header declares and the objects lack fails the link.
    if (argc > 1000) {
        struct dxc_native_window window;
        dxc_native_window_open(argv[0], 1, 1, &window);
        dxc_native_frame_begin(window.swapchain, NULL);
        dxc_native_poll_event(NULL);
        dxc_native_clipboard_write(argv[0]);
        dxc_native_set_edit_menu(NULL, 0);
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
        dxc_native_file_dialog_show(window.window, 1, DXC_FILE_DIALOG_OPEN_FILE, argv[0], argv[0], argv[0], argv[0], 0);
        dxc_native_file_dialog_cancel(1);
        dxc_native_file_dialog_next(NULL, NULL, NULL, NULL, NULL);
        dxc_native_file_dialog_take(1, NULL, 0);
    }
    if (failures == 0) printf("ok\n");
    return failures == 0 ? 0 : 1;
}
