#ifndef COMPOSE_WINDOW_CHROME_H
#define COMPOSE_WINDOW_CHROME_H

#include <stdint.h>

// The decisions behind a Compose window's caption band and its live resize on Windows,
// kept apart from the window procedure because none of them needs a window. Each is
// arithmetic on a few numbers, so a machine without Windows can compile this header and
// check the answers (extended/skiko/tests/compose-window-chrome.test.sh in
// compose-multiplatform-core-extended). The window procedure in composeWindowChrome.cc
// only gathers the numbers and acts on the answers.

#ifdef __cplusplus
extern "C" {
#endif

// What a point in the caption band is, answered for WM_NCHITTEST.
enum compose_caption_part {
    // Ordinary client area: Compose gets the mouse there.
    COMPOSE_CAPTION_CLIENT = 0,
    // The top resize border, which lived in the non-client area the window gave up.
    COMPOSE_CAPTION_TOP = 1,
    // Empty band: drags the window, double click maximises, right click opens the system menu.
    COMPOSE_CAPTION_DRAG = 2,
};

/**
 * What the point at `from_top` pixels below the window's top edge and `from_right` pixels
 * left of its right edge is, in a window whose caption band is `caption` pixels high with
 * `buttons` pixels of buttons at the trailing edge and a resize border `border` pixels thick.
 *
 * Asked only where the system's own hit test said client, so the sides, the bottom and the
 * corners have already been answered by the frame and keep their answers.
 *
 * A maximised window has no top border to resize from: it is laid out past the monitor's
 * edge and the band starts where the monitor does.
 */
static inline enum compose_caption_part compose_caption_hit(
    int32_t from_top, int32_t from_right, int maximised,
    int32_t border, int32_t caption, int32_t buttons) {
    if (!maximised && from_top < border) {
        return COMPOSE_CAPTION_TOP;
    }
    if (from_top >= caption) {
        return COMPOSE_CAPTION_CLIENT;
    }
    // The buttons are drawn by Compose and take ordinary clicks, so their strip stays client.
    if (from_right < buttons) {
        return COMPOSE_CAPTION_CLIENT;
    }
    return COMPOSE_CAPTION_DRAG;
}

/**
 * How far the top of the client area moves down from where the frame's top edge was asked
 * to be. Zero for an ordinary window: the band is client area from the very top. A maximised
 * window is laid out larger than its monitor by the resize border on every side, and
 * without this the band would sit above the screen.
 */
static inline int32_t compose_caption_top_inset(int maximised, int32_t border) {
    return maximised ? border : 0;
}

/** Scales a length in device independent pixels to a window at `dpi`. */
static inline int32_t compose_scale(int32_t dip, uint32_t dpi) {
    if (dpi == 0) {
        dpi = 96;
    }
    // Rounded to nearest, as MulDiv does.
    return (int32_t)(((int64_t)dip * dpi + 48) / 96);
}

/*
 * Drawing inside a live resize.
 *
 * While the reader drags an edge, Windows runs its own loop on the toolkit thread and moves
 * the window's edge the moment the pointer moves. Compose draws on another thread, so what
 * is inside the new edge is whatever was last presented, and the strip between the two is
 * as wide as the speed of the hand times how late the drawing is: one to two frames,
 * measured on a Compose window at 125% and 200%.
 *
 * So the size message waits, a bounded time, for a frame presented at the size the window
 * has just become, and only then lets Windows carry on. The record below is what that
 * waiting is decided from. Sizes are in pixels.
 */
struct compose_resize_sync {
    // The client size the window was last given.
    int32_t client_width;
    int32_t client_height;
    // The size of the last frame presented into this window.
    int32_t presented_width;
    int32_t presented_height;
    // How many frames have been presented into this window. Zero means nothing presents
    // through the hook (OpenGL, software rendering, or nothing drawn yet), and then there
    // is nothing to wait for: waiting would only stall the drag for the full timeout.
    uint64_t presents;
    // Between WM_ENTERSIZEMOVE and WM_EXITSIZEMOVE.
    int dragging;
};

/** The window has been given a client size. */
static inline void compose_resize_note_client(struct compose_resize_sync *sync, int32_t width, int32_t height) {
    sync->client_width = width;
    sync->client_height = height;
}

/** A frame has been presented at this size. */
static inline void compose_resize_note_present(struct compose_resize_sync *sync, int32_t width, int32_t height) {
    sync->presented_width = width;
    sync->presented_height = height;
    sync->presents++;
}

/**
 * Whether a client size arriving now has to be waited for, and the size the frame that
 * answers it will be presented at.
 *
 * The presented surface is not always the whole client area: a Swing menu bar takes a strip
 * above it. The difference between the two before the resize is carried over, so the
 * surface is expected to change by what the client area changed by.
 *
 * Not waited for: outside a drag (the frame loop runs and takes the size on its next turn),
 * when nothing presents through the hook, when the size did not change (the window was moved),
 * and when the surface already has the expected size.
 */
static inline int compose_resize_expect(
    const struct compose_resize_sync *sync, int32_t width, int32_t height,
    int32_t *expected_width, int32_t *expected_height) {
    if (!sync->dragging || sync->presents == 0) {
        return 0;
    }
    if (width == sync->client_width && height == sync->client_height) {
        return 0;
    }
    int32_t taken_width = sync->client_width - sync->presented_width;
    int32_t taken_height = sync->client_height - sync->presented_height;
    if (taken_width < 0) taken_width = 0;
    if (taken_height < 0) taken_height = 0;
    int32_t want_width = width - taken_width;
    int32_t want_height = height - taken_height;
    if (want_width <= 0 || want_height <= 0) {
        return 0;
    }
    if (want_width == sync->presented_width && want_height == sync->presented_height) {
        return 0;
    }
    *expected_width = want_width;
    *expected_height = want_height;
    return 1;
}

/**
 * Whether the wait that began when `presents_at_start` frames had been presented is over.
 *
 * Over once a frame presented after the wait began has the expected size. A frame that was
 * already on screen at that size does not count: a window dragged out and back again passes
 * through sizes it has shown before, and the frame that answers this size has not been drawn.
 */
static inline int compose_resize_satisfied(
    const struct compose_resize_sync *sync, uint64_t presents_at_start,
    int32_t expected_width, int32_t expected_height) {
    return sync->presents > presents_at_start &&
        sync->presented_width == expected_width &&
        sync->presented_height == expected_height;
}

// How long one size waits for its frame, at most. Two frames at 60Hz and some room. A
// window whose content takes longer than this to draw drags as it did before, late, and
// never stalls for longer than this per step.
#define COMPOSE_RESIZE_WAIT_MS 50

#ifdef __cplusplus
}
#endif

#endif
