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

#endif
