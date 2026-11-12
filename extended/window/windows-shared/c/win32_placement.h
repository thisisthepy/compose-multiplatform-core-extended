#ifndef DXC_WIN32_PLACEMENT_H
#define DXC_WIN32_PLACEMENT_H

#include <stdint.h>

// Where the first window goes and how big its outside is, given the work area of the
// monitor it is on.
//
// Kept apart from the window for the same reason as win32_resize.h: it is arithmetic on a
// few rectangles, and a machine with no Windows on it can compile this header and check
// the answers. All numbers are physical pixels in virtual screen coordinates, so a
// monitor left of or above the primary one has a negative origin.

/** The outside of a window, or a work area: left, top, right and bottom edges. */
struct dxc_placement_rect {
    int32_t left;
    int32_t top;
    int32_t right;
    int32_t bottom;
};

static int32_t dxc_placement_clamp(int32_t value, int32_t low, int32_t high) {
    if (value > high) value = high;
    if (value < low) value = low;
    return value;
}

/**
 * Fits the outside of the first window into a work area.
 *
 * `outer_width` and `outer_height` are the outside size the application asked for, frame
 * included. `min_width` and `min_height` are the outside size the window may not be made
 * smaller than (zero for none); a minimum larger than the work area gives way to the work
 * area, because a window that does not fit is the thing being avoided. A size that fits
 * is kept as it is.
 *
 * Without a position the window is centred on the work area. With one, `x` and `y` are
 * the wanted top left corner, moved only as far as it takes to keep the whole outside,
 * and so the caption, inside the work area.
 */
static struct dxc_placement_rect dxc_placement_fit(
    struct dxc_placement_rect work,
    int32_t outer_width,
    int32_t outer_height,
    int32_t min_width,
    int32_t min_height,
    int has_position,
    int32_t x,
    int32_t y
) {
    int32_t work_width = work.right - work.left;
    int32_t work_height = work.bottom - work.top;
    if (work_width < 1) work_width = 1;
    if (work_height < 1) work_height = 1;
    int32_t low_width = dxc_placement_clamp(min_width, 1, work_width);
    int32_t low_height = dxc_placement_clamp(min_height, 1, work_height);
    int32_t width = dxc_placement_clamp(outer_width, low_width, work_width);
    int32_t height = dxc_placement_clamp(outer_height, low_height, work_height);
    int32_t left;
    int32_t top;
    if (has_position) {
        left = dxc_placement_clamp(x, work.left, work.left + work_width - width);
        top = dxc_placement_clamp(y, work.top, work.top + work_height - height);
    } else {
        left = work.left + (work_width - width) / 2;
        top = work.top + (work_height - height) / 2;
    }
    struct dxc_placement_rect outer = {left, top, left + width, top + height};
    return outer;
}

#endif
