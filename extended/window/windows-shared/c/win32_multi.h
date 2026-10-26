// The arithmetic and bookkeeping several Win32 windows in one process need, kept apart from
// win32_window.c so the shared test can check it without a window (FR-53).
//
// Nothing here includes windows.h: a window handle is an opaque pointer to this file. The
// window table maps a handle to the state record the C file allocated for that window;
// the shared record counts the windows that keep the process-wide state (the window class,
// the DPI awareness) alive; the rest is the integer arithmetic of screen points and of
// following the pointer.
#ifndef DXC_WIN32_MULTI_H
#define DXC_WIN32_MULTI_H

#include <stddef.h>
#include <stdint.h>

// More windows than a person has open at once; a table that fills refuses the next window
// rather than growing.
#define DXC_MAX_WINDOWS 64

struct dxc_window_table {
    void *handles[DXC_MAX_WINDOWS];
    void *states[DXC_MAX_WINDOWS];
    int32_t count;
};

/** The state recorded for [handle], or NULL. */
static inline void *dxc_table_find(const struct dxc_window_table *table, const void *handle) {
    if (handle == NULL) return NULL;
    for (int32_t index = 0; index < DXC_MAX_WINDOWS; index++) {
        if (table->handles[index] == handle) return table->states[index];
    }
    return NULL;
}

/** The handle whose state is [state], or NULL. */
static inline void *dxc_table_handle_of(const struct dxc_window_table *table, const void *state) {
    if (state == NULL) return NULL;
    for (int32_t index = 0; index < DXC_MAX_WINDOWS; index++) {
        if (table->handles[index] != NULL && table->states[index] == state) return table->handles[index];
    }
    return NULL;
}

/** Records [state] under [handle]. 0 when recorded, 1 when the table is full or the handle is already in it. */
static inline int32_t dxc_table_add(struct dxc_window_table *table, void *handle, void *state) {
    if (handle == NULL || state == NULL || dxc_table_find(table, handle) != NULL) return 1;
    for (int32_t index = 0; index < DXC_MAX_WINDOWS; index++) {
        if (table->handles[index] == NULL) {
            table->handles[index] = handle;
            table->states[index] = state;
            table->count++;
            return 0;
        }
    }
    return 1;
}

/** Forgets [handle] and returns the state it had, or NULL. */
static inline void *dxc_table_remove(struct dxc_window_table *table, const void *handle) {
    if (handle == NULL) return NULL;
    for (int32_t index = 0; index < DXC_MAX_WINDOWS; index++) {
        if (table->handles[index] == handle) {
            void *state = table->states[index];
            table->handles[index] = NULL;
            table->states[index] = NULL;
            table->count--;
            return state;
        }
    }
    return NULL;
}

/**
 * What the process holds once for all its windows, counted by the windows that use it. The
 * first window to open sets it up and the last one to close takes it down.
 */
struct dxc_shared {
    int32_t users;
};

/** Counts a window in. 1 when this is the first, so the caller sets the shared state up. */
static inline int32_t dxc_shared_acquire(struct dxc_shared *shared) {
    return ++shared->users == 1 ? 1 : 0;
}

/** Counts a window out. 1 when it was the last, so the caller takes the shared state down. */
static inline int32_t dxc_shared_release(struct dxc_shared *shared) {
    if (shared->users <= 0) return 0;
    return --shared->users == 0 ? 1 : 0;
}

// Screen points to device pixels at a monitor's DPI (96 is one point to one pixel), rounded
// to the nearest, halves away from zero. A window left of or above the primary screen has a
// negative coordinate, and it must round the same way as one at the same distance the other
// side of zero.
static inline int32_t dxc_points_to_pixels(int32_t points, uint32_t dpi) {
    int64_t scaled = (int64_t)points * (int64_t)dpi;
    int64_t half = 48;
    return (int32_t)(scaled >= 0 ? (scaled + half) / 96 : -((-scaled + half) / 96));
}

static inline int32_t dxc_pixels_to_points(int32_t pixels, uint32_t dpi) {
    if (dpi == 0) return pixels;
    int64_t scaled = (int64_t)pixels * 96;
    int64_t half = (int64_t)dpi / 2;
    return (int32_t)(scaled >= 0 ? (scaled + half) / (int64_t)dpi : -((-scaled + half) / (int64_t)dpi));
}

/**
 * Where the window's outer left (or top) edge goes so the point [anchor_points] of its
 * content, measured from the content's own edge, is under the pointer at [pointer_pixels].
 * [client_offset_pixels] is how far the content's edge is inside the outer frame.
 */
static inline int32_t dxc_follow_origin(int32_t pointer_pixels, float anchor_points, uint32_t dpi,
                                        int32_t client_offset_pixels) {
    float scaled = anchor_points * (float)dpi / 96.0f;
    int32_t anchor_pixels = (int32_t)(scaled >= 0.0f ? scaled + 0.5f : scaled - 0.5f);
    return pointer_pixels - anchor_pixels - client_offset_pixels;
}

/** What a follow turn does: keep going, or end because the button went up or Escape was pressed. */
enum {
    DXC_FOLLOW_KEEP = 0,
    DXC_FOLLOW_RELEASED = 1,
    DXC_FOLLOW_CANCELLED = 2,
};

/** Escape wins over the button: a press of Escape while the button is still down cancels. */
static inline int32_t dxc_follow_decide(int32_t button_down, int32_t escape_down) {
    if (escape_down) return DXC_FOLLOW_CANCELLED;
    return button_down ? DXC_FOLLOW_KEEP : DXC_FOLLOW_RELEASED;
}

// A pump that waits for something to arrive waits once per turn. With one window the caller
// pumps once a turn and every pump waits, as it always has. With several windows the caller
// still pumps once a turn, on one of them, and a second pump that follows within
// [quiet_micros] of the end of the last is the same turn: it drains and does not wait again.
static inline int32_t dxc_pump_waits(int32_t open_windows, int64_t now_micros, int64_t last_end_micros,
                                     int64_t quiet_micros) {
    if (open_windows <= 1) return 1;
    return now_micros - last_end_micros >= quiet_micros ? 1 : 0;
}

#endif
