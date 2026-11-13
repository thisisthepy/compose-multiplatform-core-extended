#ifndef DXC_WIN32_CAPTION_H
#define DXC_WIN32_CAPTION_H

#include <stdint.h>

// Where a point in the caption strip lands, when the window draws its own caption.
//
// Kept apart from the window because none of it needs one: it is arithmetic on a point, a
// scale and two flags, so the shared test checks it at every scale without opening a
// window. The window procedure turns the answer into the WM_NCHITTEST value.
//
// The renderer draws the three buttons at the trailing edge, Windows 11's 46 by 32 dip each.
// Minimise and close stay client area so they receive ordinary mouse input. Maximise answers
// as the maximise button, because that is the only way Windows 11 shows Snap Layouts on
// hover; the window procedure forwards the non-client press and move there to the scene, so
// the same Kotlin button still hovers, presses and toggles.
//
// The metrics are Win32Chrome's in common (Kotlin), which is what the scene lays the buttons
// out with; scripts/check-windows-caption-metrics.sh fails when the two stop agreeing.

#define DXC_CAPTION_HEIGHT_DIP 32
#define DXC_CAPTION_BUTTON_WIDTH_DIP 46
#define DXC_CAPTION_BUTTON_COUNT 3

enum dxc_caption_zone {
    DXC_CAPTION_ZONE_CLIENT = 0,
    DXC_CAPTION_ZONE_CAPTION = 1,
    DXC_CAPTION_ZONE_TOP = 2,
    DXC_CAPTION_ZONE_MINIMISE = 3,
    DXC_CAPTION_ZONE_MAXIMISE = 4,
    DXC_CAPTION_ZONE_CLOSE = 5,
};

/** A length in device independent pixels, at `dpi`. 96 is 100%. */
static inline int32_t dxc_caption_scale(int32_t dip, int32_t dpi) {
    if (dpi <= 0) {
        dpi = 96;
    }
    return (int32_t)(((int64_t)dip * dpi + 48) / 96);
}

/**
 * Which part of the caption the point is over.
 *
 * `x` and `y` are measured from the window's top left corner, `width` is the window's
 * width, all in physical pixels. `band` is the top resize band, which only a resizable
 * window that is not maximised has.
 */
static inline enum dxc_caption_zone dxc_caption_zone_at(
    int32_t x, int32_t y, int32_t width, int32_t dpi, int32_t band, int32_t resizable, int32_t zoomed
) {
    if (resizable && !zoomed && y < band) {
        return DXC_CAPTION_ZONE_TOP;
    }
    if (y < 0 || y >= dxc_caption_scale(DXC_CAPTION_HEIGHT_DIP, dpi)) {
        return DXC_CAPTION_ZONE_CLIENT;
    }
    int32_t button = dxc_caption_scale(DXC_CAPTION_BUTTON_WIDTH_DIP, dpi);
    int32_t from_right = width - x;
    if (from_right <= 0) {
        return DXC_CAPTION_ZONE_CLIENT;
    }
    if (from_right <= button) {
        return DXC_CAPTION_ZONE_CLOSE;
    }
    if (from_right <= button * 2) {
        return DXC_CAPTION_ZONE_MAXIMISE;
    }
    if (from_right <= button * 3) {
        return DXC_CAPTION_ZONE_MINIMISE;
    }
    return DXC_CAPTION_ZONE_CAPTION;
}

/*
 * What the scene is told of the pointer while it crosses between the maximise button, which
 * is non-client, and everything else the scene draws, which is client area.
 *
 * The scene has one pointer and no notion of the two areas. It hears a move for every place
 * the pointer is over one of its buttons, and one move to DXC_CAPTION_OUTSIDE when the
 * pointer is over none of what it draws any more, which is what ends a hover. Windows says
 * the pointer left each area with a message of its own (WM_MOUSELEAVE, WM_NCMOUSELEAVE) that
 * may arrive after the first move in the other area, so a leave is passed on only when the
 * pointer did not go on to the other area, and never while a button is held: a press keeps
 * the mouse captured and its moves keep arriving, wherever the pointer is.
 */

/** The coordinate, on both axes, of the move that tells the scene the pointer is over nothing. */
#define DXC_CAPTION_OUTSIDE (-1)

enum dxc_caption_forward {
    DXC_CAPTION_FORWARD_NONE = 0,
    /** The pointer is over the maximise button: a move at that point. */
    DXC_CAPTION_FORWARD_MOVE = 1,
    /** The pointer is over nothing the scene draws: a move to DXC_CAPTION_OUTSIDE. */
    DXC_CAPTION_FORWARD_LEAVE = 2,
};

/** One window's pointer, as far as the caption needs it. Zero to begin with. */
struct dxc_caption_pointer {
    /** The last non-client move was over the maximise button and nothing has said it left. */
    int32_t over_maximise;
};

/** A non-client move (WM_NCMOUSEMOVE); `over_maximise` is whether its hit code is the maximise button. */
static inline enum dxc_caption_forward dxc_caption_nc_move(struct dxc_caption_pointer *pointer, int32_t over_maximise) {
    int32_t was = pointer->over_maximise;
    pointer->over_maximise = over_maximise ? 1 : 0;
    if (over_maximise) {
        return DXC_CAPTION_FORWARD_MOVE;
    }
    // Off the button onto the caption or the top band: still non-client, so no leave follows.
    return was ? DXC_CAPTION_FORWARD_LEAVE : DXC_CAPTION_FORWARD_NONE;
}

/** The pointer left the non-client area (WM_NCMOUSELEAVE). `held` is whether a button is down or the mouse captured. */
static inline enum dxc_caption_forward dxc_caption_nc_leave(struct dxc_caption_pointer *pointer, int32_t held) {
    int32_t was = pointer->over_maximise;
    pointer->over_maximise = 0;
    return was && !held ? DXC_CAPTION_FORWARD_LEAVE : DXC_CAPTION_FORWARD_NONE;
}

/** A client move (WM_MOUSEMOVE), which the scene hears as it is: the pointer is not over the maximise button. */
static inline void dxc_caption_client_move(struct dxc_caption_pointer *pointer) {
    pointer->over_maximise = 0;
}

/** The pointer left the client area (WM_MOUSELEAVE). `held` as for the non-client leave. */
static inline enum dxc_caption_forward dxc_caption_client_leave(const struct dxc_caption_pointer *pointer, int32_t held) {
    // Onto the maximise button: its move already told the scene where the pointer is.
    return pointer->over_maximise || held ? DXC_CAPTION_FORWARD_NONE : DXC_CAPTION_FORWARD_LEAVE;
}

#endif
