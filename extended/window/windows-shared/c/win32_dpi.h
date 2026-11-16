#ifndef DXC_WIN32_DPI_H
#define DXC_WIN32_DPI_H

#include <stdint.h>

// What a window's client area comes to when its DPI changes.
//
// A window keeps its size in points across a change of scale, as the macOS window does and
// the X11 windows do (X11Scale.sizeAtScale in common): 320x240 points are 640x480 pixels at
// 192 DPI and 400x300 at 120. Windows does not do this for a per monitor aware window. It
// hands the window, in WM_DPICHANGED, a rectangle it scaled from the old outer rectangle
// without knowing this window's frame (its caption strip is client area, see WM_NCCALCSIZE
// in win32_window.c), and the frame does not scale linearly with the DPI. Applied as it
// came, a client area of 640x480 pixels at 192 DPI became 398x297 at 120, not 400x300.
//
// So the window remembers its size, and the client area it is given at a new DPI is that
// size restated at the new DPI. The size is remembered as a length at a DPI rather than as
// pixels re-derived at every change: a size the application gave in points is kept in
// points (at 96), a size the user dragged to is kept in the pixels of the DPI it was dragged
// at. Restating from pixels each time would drift, since 333 points are 416 pixels at 120
// DPI, and 416 pixels at 120 DPI are 499 at 144 where 333 points are 500.
//
// Kept apart from the window because it is arithmetic, so the MSVC test checks it where no
// window can be made, and it compiles without Windows.

/** A size of [width] x [height] pixels at [dpi]. A dpi of 96 is a size in points; 0 is none yet. */
struct dxc_dpi_size {
    int32_t width;
    int32_t height;
    uint32_t dpi;
};

/**
 * [length] pixels at [from] DPI, restated at [to] DPI: the same length in points times the
 * new scale, rounded to the nearest pixel with a half rounded up, and at least 1. The rule of
 * X11Scale.pixelsAtScale and of dxc_points_to_pixels, which is the case [from] == 96.
 */
static inline int32_t dxc_dpi_length(int32_t length, uint32_t from, uint32_t to) {
    if (from == 0 || to == 0 || from == to) return length;
    int64_t scaled = ((int64_t)length * (int64_t)to * 2 + (int64_t)from) / ((int64_t)from * 2);
    if (scaled < 1) return 1;
    if (scaled > INT32_MAX) return INT32_MAX;
    return (int32_t)scaled;
}

/**
 * The size to remember once the client area is [width] x [height] pixels at [dpi]. The size
 * already remembered stays when it comes to exactly those pixels at that DPI, so a size given
 * in points stays in points through the WM_SIZE that applies it; any other size replaces it.
 */
static inline struct dxc_dpi_size dxc_dpi_size_note(struct dxc_dpi_size remembered, int32_t width,
                                                    int32_t height, uint32_t dpi) {
    if (remembered.dpi != 0 && dxc_dpi_length(remembered.width, remembered.dpi, dpi) == width &&
        dxc_dpi_length(remembered.height, remembered.dpi, dpi) == height) {
        return remembered;
    }
    struct dxc_dpi_size noted = {width, height, dpi};
    return noted;
}

/**
 * The client area, in pixels at [dpi], for the [remembered] size, never smaller than the
 * window's minimum of [min_width] x [min_height] points (0 for none).
 */
static inline void dxc_dpi_size_client(struct dxc_dpi_size remembered, uint32_t dpi, int32_t min_width,
                                       int32_t min_height, int32_t *width, int32_t *height) {
    int32_t w = dxc_dpi_length(remembered.width, remembered.dpi, dpi);
    int32_t h = dxc_dpi_length(remembered.height, remembered.dpi, dpi);
    if (min_width > 0) {
        int32_t least = dxc_dpi_length(min_width, 96, dpi);
        if (w < least) w = least;
    }
    if (min_height > 0) {
        int32_t least = dxc_dpi_length(min_height, 96, dpi);
        if (h < least) h = least;
    }
    *width = w;
    *height = h;
}

/**
 * 1 when a change of DPI resizes the window to its remembered size. A maximised or snapped
 * window fills the place it was given and takes the rectangle Windows suggests; a minimised
 * one has no client area to keep. Each of those gets its size back when it is restored.
 */
static inline int32_t dxc_dpi_change_resizes(int32_t zoomed, int32_t iconic, int32_t arranged) {
    return zoomed || iconic || arranged ? 0 : 1;
}

#endif
