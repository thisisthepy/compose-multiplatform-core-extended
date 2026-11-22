#ifndef DXC_WIN32_PROBE_JUDGE_H
#define DXC_WIN32_PROBE_JUDGE_H

#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

// The resize probe's picture and the arithmetic of its judges (c/test/win32_resize_probe.c).
//
// Kept apart from the probe because none of it needs Windows: it paints into a BGRA buffer
// and reads one back. c/test/win32_shared_test.c checks it, so the judges' own maths is
// tested on a machine with no desktop to capture.
//
// The picture of the drag modes that move the window's origin (drag-left, drag-top,
// drag-topleft, drag-fast) is the probe's grey frame with its magenta strips at the right and
// the bottom, plus:
//  - four 8x8 markers, a colour each, 16 px in from each corner of the frame;
//  - a row of 4x4 blocks that spells the frame's width and height (a sync pair, then 16 bits
//    of width and 16 of height, most significant first, white one, black zero), 32 px from
//    the left and 40 px from the top.
// So a composed frame says where the picture is (the markers) and what size it was drawn for
// (the blocks), and the window's true rectangle comes from the border DWM draws around it in
// a colour that occurs nowhere in the picture (DWMWA_BORDER_COLOR, pure blue).

#define PROBE_STRIP 4
#define PROBE_MARK_SIZE 8
#define PROBE_MARK_INSET 16
#define PROBE_CODE_BLOCK 4
#define PROBE_CODE_BITS 16
#define PROBE_CODE_BLOCKS (2 + 2 * PROBE_CODE_BITS)
#define PROBE_CODE_X 32
#define PROBE_CODE_Y 40

// Pixels as a 32-bit BGRA word reads on a little-endian machine: 0xAARRGGBB.
#define PROBE_GREY 0xFF505050u
#define PROBE_MAGENTA 0xFFFF00FFu
#define PROBE_CODE_ONE 0xFFFFFFFFu
#define PROBE_CODE_ZERO 0xFF000000u
// The border colour as DWM takes it (a COLORREF, 0x00BBGGRR): pure blue.
#define PROBE_BORDER_COLORREF 0x00FF0000u

enum { PROBE_MARK_TOP_LEFT, PROBE_MARK_TOP_RIGHT, PROBE_MARK_BOTTOM_LEFT, PROBE_MARK_BOTTOM_RIGHT, PROBE_MARKS };

// Red, yellow, cyan, orange: none is blue, green, grey, magenta, black or white.
static const uint32_t probe_mark_colors[PROBE_MARKS] = {0xFFFF0000u, 0xFFFFFF00u, 0xFF00FFFFu, 0xFFFF8000u};
static const char *const probe_mark_names[PROBE_MARKS] = {"top-left", "top-right", "bottom-left", "bottom-right"};

/** A rectangle of pixels, right and bottom exclusive. */
struct probe_box {
    int32_t left, top, right, bottom;
};

/** A BGRA image: bytes, bytes a row, size in pixels. */
struct probe_image {
    const uint8_t *bits;
    int32_t pitch;
    int32_t width;
    int32_t height;
};

/** Where marker [which] sits in a frame of [width] by [height]. */
static struct probe_box probe_mark_box(int which, int32_t width, int32_t height) {
    struct probe_box box;
    int right = which == PROBE_MARK_TOP_RIGHT || which == PROBE_MARK_BOTTOM_RIGHT;
    int bottom = which == PROBE_MARK_BOTTOM_LEFT || which == PROBE_MARK_BOTTOM_RIGHT;
    box.left = right ? width - PROBE_MARK_INSET - PROBE_MARK_SIZE : PROBE_MARK_INSET;
    box.top = bottom ? height - PROBE_MARK_INSET - PROBE_MARK_SIZE : PROBE_MARK_INSET;
    box.right = box.left + PROBE_MARK_SIZE;
    box.bottom = box.top + PROBE_MARK_SIZE;
    return box;
}

/** Whether a frame of this size has room for the markers and the size code without overlap. */
static int probe_marks_fit(int32_t width, int32_t height) {
    return width >= PROBE_CODE_X + PROBE_CODE_BLOCKS * PROBE_CODE_BLOCK + PROBE_MARK_INSET + PROBE_MARK_SIZE &&
           height >= PROBE_CODE_Y + PROBE_CODE_BLOCK + PROBE_MARK_INSET + PROBE_MARK_SIZE + 4;
}

/** The blocks that spell [width] and [height]: 1 white, 0 black. */
static void probe_code_bits(int32_t width, int32_t height, uint8_t bits[PROBE_CODE_BLOCKS]) {
    bits[0] = 1;
    bits[1] = 0;
    for (int i = 0; i < PROBE_CODE_BITS; i++) {
        bits[2 + i] = (uint8_t)(((uint32_t)width >> (PROBE_CODE_BITS - 1 - i)) & 1u);
        bits[2 + PROBE_CODE_BITS + i] = (uint8_t)(((uint32_t)height >> (PROBE_CODE_BITS - 1 - i)) & 1u);
    }
}

/**
 * The size spelled by blocks read back (0, 1, or 2 for a block that was neither black nor
 * white). One when the sync pair is there, every block was read and both sizes are above zero.
 */
static int probe_code_read(const uint8_t bits[PROBE_CODE_BLOCKS], int32_t *width, int32_t *height) {
    if (bits[0] != 1 || bits[1] != 0) return 0;
    uint32_t w = 0, h = 0;
    for (int i = 0; i < PROBE_CODE_BITS; i++) {
        uint8_t a = bits[2 + i], b = bits[2 + PROBE_CODE_BITS + i];
        if (a > 1 || b > 1) return 0;
        w = (w << 1) | a;
        h = (h << 1) | b;
    }
    if (w == 0 || h == 0) return 0;
    *width = (int32_t)w;
    *height = (int32_t)h;
    return 1;
}

static void probe_fill_box(uint8_t *pixels, int32_t row_bytes, struct probe_box box, uint32_t color) {
    for (int32_t y = box.top; y < box.bottom; y++) {
        uint32_t *row = (uint32_t *)(pixels + (size_t)y * (size_t)row_bytes);
        for (int32_t x = box.left; x < box.right; x++) row[x] = color;
    }
}

/**
 * The marked picture: grey, the magenta strips along the right and the bottom (as the plain
 * picture), the four markers and the size code, where the frame has room for them.
 */
static void probe_fill_marked(uint8_t *pixels, int32_t row_bytes, int32_t width, int32_t height) {
    for (int32_t y = 0; y < height; y++) {
        uint32_t *row = (uint32_t *)(pixels + (size_t)y * (size_t)row_bytes);
        for (int32_t x = 0; x < width; x++) {
            int strip = x >= width - PROBE_STRIP || y >= height - PROBE_STRIP;
            row[x] = strip ? PROBE_MAGENTA : PROBE_GREY;
        }
    }
    if (!probe_marks_fit(width, height)) return;
    for (int which = 0; which < PROBE_MARKS; which++) {
        probe_fill_box(pixels, row_bytes, probe_mark_box(which, width, height), probe_mark_colors[which]);
    }
    uint8_t bits[PROBE_CODE_BLOCKS];
    probe_code_bits(width, height, bits);
    for (int i = 0; i < PROBE_CODE_BLOCKS; i++) {
        struct probe_box block = {PROBE_CODE_X + i * PROBE_CODE_BLOCK, PROBE_CODE_Y,
                                  PROBE_CODE_X + (i + 1) * PROBE_CODE_BLOCK, PROBE_CODE_Y + PROBE_CODE_BLOCK};
        probe_fill_box(pixels, row_bytes, block, bits[i] ? PROBE_CODE_ONE : PROBE_CODE_ZERO);
    }
}

// What a captured pixel is. Thresholds, not equality: DWM may blend an edge pixel.
static const uint8_t *probe_pixel(const struct probe_image *image, int32_t x, int32_t y) {
    return image->bits + (size_t)y * (size_t)image->pitch + (size_t)x * 4;
}

/** The border DWM draws in PROBE_BORDER_COLORREF. */
static int probe_is_border(const uint8_t *bgra) {
    return bgra[0] >= 160 && bgra[1] <= 80 && bgra[2] <= 80;
}

/** The probe's green backdrop, or green darkened by a shadow (as the right-edge judge reads it). */
static int probe_is_backdrop(const uint8_t *bgra) {
    int b = bgra[0], g = bgra[1], r = bgra[2];
    return g > r + 30 && g > b + 30;
}

static int probe_is_mark(int which, const uint8_t *bgra) {
    int b = bgra[0], g = bgra[1], r = bgra[2];
    switch (which) {
    case PROBE_MARK_TOP_LEFT: return r >= 200 && g <= 60 && b <= 60;
    case PROBE_MARK_TOP_RIGHT: return r >= 200 && g >= 200 && b <= 60;
    case PROBE_MARK_BOTTOM_LEFT: return r <= 60 && g >= 200 && b >= 200;
    default: return r >= 200 && g >= 90 && g <= 170 && b <= 60;
    }
}

/** A size-code block: 1 white, 0 black, 2 neither. */
static uint8_t probe_code_level(const uint8_t *bgra) {
    if (bgra[0] >= 200 && bgra[1] >= 200 && bgra[2] >= 200) return 1;
    if (bgra[0] <= 55 && bgra[1] <= 55 && bgra[2] <= 55) return 0;
    return 2;
}

static int probe_compare_ints(const void *a, const void *b) {
    int32_t x = *(const int32_t *)a, y = *(const int32_t *)b;
    return x < y ? -1 : x > y;
}

/** The value most of [values] share, and how many share it. Sorts [values]. */
static int32_t probe_most_common(int32_t *values, int count, int *votes) {
    qsort(values, (size_t)count, sizeof values[0], probe_compare_ints);
    int32_t best = count > 0 ? values[0] : 0;
    int best_run = 0;
    for (int i = 0; i < count;) {
        int j = i;
        while (j < count && values[j] == values[i]) j++;
        if (j - i > best_run) {
            best_run = j - i;
            best = values[i];
        }
        i = j;
    }
    *votes = best_run;
    return best;
}

/**
 * The window's border as found in a captured image: the outermost border pixel of each side
 * (inclusive), and the area inside the border (the picture's area), right and bottom exclusive.
 */
struct probe_border {
    int32_t left, top, right, bottom;
    struct probe_box inner;
};

#define PROBE_BORDER_SAMPLES 4096

/** The first and the last border pixel down column [x], or -1 and -1. */
static void probe_column_extent(const struct probe_image *image, int32_t x, int32_t *first, int32_t *last) {
    *first = -1;
    *last = -1;
    for (int32_t y = 0; y < image->height; y++) {
        if (probe_is_border(probe_pixel(image, x, y))) {
            if (*first < 0) *first = y;
            *last = y;
        }
    }
}

/**
 * Finds the border in [image]: on every [step]th row the first and the last border pixel,
 * and the value most rows agree on is the left and the right side (a corner or a stray pixel
 * is outvoted); then the same down the columns between them for the top and the bottom. The
 * border's thickness on each side is counted inward from it, at the middle of that side. One
 * when a rectangle was found: at least 8 rows, and half of those that saw any border pixel,
 * agree on each side, and it is at least 32 px wide and high.
 *
 * Should DWM draw no line along the top (or the bottom) of a window whose client area covers
 * its top, the side lines' ends stand for it, with a thickness of zero: the columns then
 * agree on one line only, and it is the one at the side lines' other end.
 */
static int probe_find_border(const struct probe_image *image, int32_t step, struct probe_border *border) {
    static int32_t firsts[PROBE_BORDER_SAMPLES], lasts[PROBE_BORDER_SAMPLES];
    if (step < 1) step = 1;
    int rows = 0;
    for (int32_t y = 0; y < image->height && rows < PROBE_BORDER_SAMPLES; y += step) {
        int32_t first = -1, last = -1;
        for (int32_t x = 0; x < image->width; x++) {
            if (probe_is_border(probe_pixel(image, x, y))) {
                if (first < 0) first = x;
                last = x;
            }
        }
        if (first < 0) continue;
        firsts[rows] = first;
        lasts[rows] = last;
        rows++;
    }
    if (rows < 8) return 0;
    int votes_left = 0, votes_right = 0;
    int32_t left = probe_most_common(firsts, rows, &votes_left);
    int32_t right = probe_most_common(lasts, rows, &votes_right);
    if (votes_left < 8 || votes_right < 8 || votes_left * 2 < rows || votes_right * 2 < rows || right - left < 32) {
        return 0;
    }
    // Where the side lines begin and end.
    int32_t left_first, left_last, right_first, right_last;
    probe_column_extent(image, left, &left_first, &left_last);
    probe_column_extent(image, right, &right_first, &right_last);
    if (left_first < 0 || right_first < 0) return 0;
    int32_t side_top = left_first < right_first ? left_first : right_first;
    int32_t side_bottom = left_last > right_last ? left_last : right_last;
    int columns = 0;
    for (int32_t x = left + 16; x <= right - 16 && columns < PROBE_BORDER_SAMPLES; x += step) {
        int32_t first = -1, last = -1;
        probe_column_extent(image, x, &first, &last);
        if (first < 0) continue;
        firsts[columns] = first;
        lasts[columns] = last;
        columns++;
    }
    int top_line = 0, bottom_line = 0;
    int32_t top = side_top, bottom = side_bottom;
    if (columns >= 8) {
        int votes_top = 0, votes_bottom = 0;
        int32_t first_line = probe_most_common(firsts, columns, &votes_top);
        int32_t last_line = probe_most_common(lasts, columns, &votes_bottom);
        if (votes_top >= 8 && votes_top * 2 >= columns) {
            if (last_line - first_line >= 32 && votes_bottom >= 8 && votes_bottom * 2 >= columns) {
                top = first_line;
                bottom = last_line;
                top_line = bottom_line = 1;
            } else if (first_line - side_top > side_bottom - first_line) {
                bottom = first_line;  // the one line is at the bottom
                bottom_line = 1;
            } else {
                top = first_line;
                top_line = 1;
            }
        }
    }
    if (bottom - top < 32) return 0;
    // Thickness, counted inward at the middle of each side (at most 4 px).
    int32_t middle_y = (top + bottom) / 2, middle_x = (left + right) / 2;
    int32_t thick_left = 0, thick_right = 0, thick_top = 0, thick_bottom = 0;
    while (thick_left < 4 && probe_is_border(probe_pixel(image, left + thick_left, middle_y))) thick_left++;
    while (thick_right < 4 && probe_is_border(probe_pixel(image, right - thick_right, middle_y))) thick_right++;
    while (top_line && thick_top < 4 && probe_is_border(probe_pixel(image, middle_x, top + thick_top))) thick_top++;
    while (bottom_line && thick_bottom < 4 && probe_is_border(probe_pixel(image, middle_x, bottom - thick_bottom))) {
        thick_bottom++;
    }
    if (thick_left == 0 || thick_right == 0 || (top_line && thick_top == 0) || (bottom_line && thick_bottom == 0)) {
        return 0;
    }
    border->left = left;
    border->top = top;
    border->right = right;
    border->bottom = bottom;
    border->inner.left = left + thick_left;
    border->inner.top = top + thick_top;
    border->inner.right = right - thick_right + 1;
    border->inner.bottom = bottom - thick_bottom + 1;
    return border->inner.right - border->inner.left >= 32 && border->inner.bottom - border->inner.top >= 32;
}

/**
 * The bounding box of marker [which]'s pixels inside [area]: every second row first, then
 * every row and column around what that found. One when any was found.
 */
static int probe_find_mark(const struct probe_image *image, struct probe_box area, int which, struct probe_box *found) {
    if (area.left < 0) area.left = 0;
    if (area.top < 0) area.top = 0;
    if (area.right > image->width) area.right = image->width;
    if (area.bottom > image->height) area.bottom = image->height;
    int32_t min_x = INT32_MAX, min_y = INT32_MAX, max_x = -1, max_y = -1;
    for (int32_t y = area.top; y < area.bottom; y += 2) {
        for (int32_t x = area.left; x < area.right; x++) {
            if (probe_is_mark(which, probe_pixel(image, x, y))) {
                if (x < min_x) min_x = x;
                if (x > max_x) max_x = x;
                if (y < min_y) min_y = y;
                if (y > max_y) max_y = y;
            }
        }
    }
    if (max_x < 0) return 0;
    struct probe_box near_box = {min_x - 4, min_y - 4, max_x + 5, max_y + 5};
    if (near_box.left < area.left) near_box.left = area.left;
    if (near_box.top < area.top) near_box.top = area.top;
    if (near_box.right > area.right) near_box.right = area.right;
    if (near_box.bottom > area.bottom) near_box.bottom = area.bottom;
    min_x = min_y = INT32_MAX;
    max_x = max_y = -1;
    for (int32_t y = near_box.top; y < near_box.bottom; y++) {
        for (int32_t x = near_box.left; x < near_box.right; x++) {
            if (probe_is_mark(which, probe_pixel(image, x, y))) {
                if (x < min_x) min_x = x;
                if (x > max_x) max_x = x;
                if (y < min_y) min_y = y;
                if (y > max_y) max_y = y;
            }
        }
    }
    found->left = min_x;
    found->top = min_y;
    found->right = max_x + 1;
    found->bottom = max_y + 1;
    return 1;
}

/** Whether two boxes share a pixel. An empty box meets nothing. */
static int probe_boxes_meet(struct probe_box a, struct probe_box b) {
    if (a.right <= a.left || a.bottom <= a.top || b.right <= b.left || b.bottom <= b.top) return 0;
    return a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom;
}

static struct probe_box probe_grow(struct probe_box box, int32_t by) {
    box.left -= by;
    box.top -= by;
    box.right += by;
    box.bottom += by;
    return box;
}

/** Where marker [which] is looked for inside the border: its half of the area, top or bottom. */
static struct probe_box probe_mark_area(int which, struct probe_box inner) {
    struct probe_box area = inner;
    int32_t middle = inner.top + (inner.bottom - inner.top) / 2;
    if (which == PROBE_MARK_TOP_LEFT || which == PROBE_MARK_TOP_RIGHT) {
        area.bottom = middle;
    } else {
        area.top = middle;
    }
    return area;
}

/**
 * The size the picture says it was drawn for, read from the blocks to the right of and below
 * the top-left marker found at [top_left]. One when it reads.
 */
static int probe_read_size(const struct probe_image *image, struct probe_box top_left, int32_t *width, int32_t *height) {
    if (top_left.right - top_left.left != PROBE_MARK_SIZE || top_left.bottom - top_left.top != PROBE_MARK_SIZE) {
        return 0;
    }
    int32_t x0 = top_left.left + (PROBE_CODE_X - PROBE_MARK_INSET);
    int32_t y = top_left.top + (PROBE_CODE_Y - PROBE_MARK_INSET) + PROBE_CODE_BLOCK / 2;
    if (y < 0 || y >= image->height) return 0;
    uint8_t bits[PROBE_CODE_BLOCKS];
    for (int i = 0; i < PROBE_CODE_BLOCKS; i++) {
        int32_t x = x0 + i * PROBE_CODE_BLOCK + PROBE_CODE_BLOCK / 2;
        if (x < 0 || x >= image->width) return 0;
        bits[i] = probe_code_level(probe_pixel(image, x, y));
    }
    return probe_code_read(bits, width, height);
}

/**
 * What a frame at rest says about the geometry: how far each far marker's outer edge is
 * from the inner edge of the border (16 when the border sits just outside the picture), and
 * how much larger the picture is than the area inside the border (0, or 1 where the border
 * covers a row or a column of the picture). Every later frame is judged against these.
 */
struct probe_calibration {
    int ok;
    int32_t top_right_x, bottom_right_x;  // inner.right - marker.right
    int32_t bottom_left_y, bottom_right_y;  // inner.bottom - marker.bottom
    int32_t extra_width, extra_height;  // picture size - inner size
    int32_t picture_width, picture_height;
};

/**
 * Calibrates from an image of the window at rest whose picture is [width] by [height] (the
 * client size). Zero, with ok 0, when the border, a marker or the size code is not found, or
 * the size code is not the client size.
 */
static int probe_calibrate(const struct probe_image *image, int32_t width, int32_t height, struct probe_calibration *calibration) {
    memset(calibration, 0, sizeof *calibration);
    struct probe_border border;
    if (!probe_find_border(image, 2, &border)) return 0;
    struct probe_box marks[PROBE_MARKS];
    for (int which = 0; which < PROBE_MARKS; which++) {
        if (!probe_find_mark(image, probe_mark_area(which, border.inner), which, &marks[which])) return 0;
    }
    int32_t picture_width = 0, picture_height = 0;
    if (!probe_read_size(image, marks[PROBE_MARK_TOP_LEFT], &picture_width, &picture_height)) return 0;
    calibration->picture_width = picture_width;
    calibration->picture_height = picture_height;
    if (picture_width != width || picture_height != height) return 0;
    calibration->top_right_x = border.inner.right - marks[PROBE_MARK_TOP_RIGHT].right;
    calibration->bottom_right_x = border.inner.right - marks[PROBE_MARK_BOTTOM_RIGHT].right;
    calibration->bottom_left_y = border.inner.bottom - marks[PROBE_MARK_BOTTOM_LEFT].bottom;
    calibration->bottom_right_y = border.inner.bottom - marks[PROBE_MARK_BOTTOM_RIGHT].bottom;
    calibration->extra_width = picture_width - (border.inner.right - border.inner.left);
    calibration->extra_height = picture_height - (border.inner.bottom - border.inner.top);
    calibration->ok = 1;
    return 1;
}

// How far a far marker may sit from where the border says it should, in px: rounding of the
// border's place at a fractional scale.
#define PROBE_ANCHOR_TOLERANCE 1

/**
 * How far the picture is shifted from where the border puts a far edge: [expected] is the
 * calibrated distance from the border's inner edge to the marker's outer edge, [inner_edge]
 * the border's inner edge in this frame, [mark_edge] the marker's outer edge. Positive: the
 * picture sits left of (or above) where it belongs.
 */
static int32_t probe_shift(int32_t inner_edge, int32_t expected, int32_t mark_edge) {
    return (inner_edge - expected) - mark_edge;
}

/** The anchoring judge's answer for one frame. */
struct probe_anchor {
    int faulty;
    int missing;  // a far marker not found
    int cut;  // a far marker not 8 px across the axis it is judged on (cut or doubled)
    int covered;  // far markers not judged: the pointer was over them
    int32_t shift;  // the largest shift of the four checks, by size, with its sign
    int worst;  // which marker it was (PROBE_MARK_*)
};

static void probe_anchor_take(struct probe_anchor *anchor, int32_t shift, int which) {
    int32_t size = shift < 0 ? -shift : shift;
    int32_t worst = anchor->shift < 0 ? -anchor->shift : anchor->shift;
    if (size > worst) {
        anchor->shift = shift;
        anchor->worst = which;
    }
    if (size > PROBE_ANCHOR_TOLERANCE) anchor->faulty = 1;
}

/**
 * Where far marker [which] belongs in a frame in its place, grown by 8 px: across the judged
 * axis from the calibration, along the other from the border's inner corner.
 */
static struct probe_box probe_mark_home(int which, const struct probe_border *border,
                                        const struct probe_calibration *calibration) {
    struct probe_box box;
    struct probe_box in = border->inner;
    int right = which == PROBE_MARK_TOP_RIGHT || which == PROBE_MARK_BOTTOM_RIGHT;
    int bottom = which == PROBE_MARK_BOTTOM_LEFT || which == PROBE_MARK_BOTTOM_RIGHT;
    int32_t from_right = which == PROBE_MARK_TOP_RIGHT ? calibration->top_right_x : calibration->bottom_right_x;
    int32_t from_bottom = which == PROBE_MARK_BOTTOM_LEFT ? calibration->bottom_left_y : calibration->bottom_right_y;
    box.right = right ? in.right - from_right : in.left + PROBE_MARK_INSET + PROBE_MARK_SIZE;
    box.left = box.right - PROBE_MARK_SIZE;
    box.bottom = bottom ? in.bottom - from_bottom : in.top + PROBE_MARK_INSET + PROBE_MARK_SIZE;
    box.top = box.bottom - PROBE_MARK_SIZE;
    return probe_grow(box, 8);
}

/**
 * Anchoring: the right markers' right edges must sit at the border's right less the
 * calibrated distance, and the bottom markers' bottom edges at its bottom less it, within
 * PROBE_ANCHOR_TOLERANCE. A marker missing, or cut across the judged axis, is faulty too.
 *
 * [pointer] is where the pointer is drawn in this frame, should the capture carry it (a
 * machine without a hardware cursor): a marker it covers, where it was found or where it
 * belongs, is not judged in this frame and is counted as covered instead.
 */
static void probe_judge_anchor(const struct probe_image *image, const struct probe_border *border,
                               const struct probe_calibration *calibration, struct probe_box pointer,
                               struct probe_anchor *anchor) {
    memset(anchor, 0, sizeof *anchor);
    anchor->worst = -1;
    static const int far_marks[3] = {PROBE_MARK_TOP_RIGHT, PROBE_MARK_BOTTOM_LEFT, PROBE_MARK_BOTTOM_RIGHT};
    for (int i = 0; i < 3; i++) {
        int which = far_marks[i];
        struct probe_box mark = {0, 0, 0, 0};
        int found = probe_find_mark(image, probe_mark_area(which, border->inner), which, &mark);
        if (probe_boxes_meet(pointer, probe_mark_home(which, border, calibration)) ||
            (found && probe_boxes_meet(pointer, probe_grow(mark, 2)))) {
            anchor->covered++;
            continue;
        }
        if (!found) {
            anchor->missing++;
            anchor->faulty = 1;
            if (anchor->worst < 0) anchor->worst = which;
            continue;
        }
        if (which != PROBE_MARK_BOTTOM_LEFT) {
            int32_t expected = which == PROBE_MARK_TOP_RIGHT ? calibration->top_right_x : calibration->bottom_right_x;
            probe_anchor_take(anchor, probe_shift(border->inner.right, expected, mark.right), which);
            int32_t across = mark.right - mark.left;
            if (across < PROBE_MARK_SIZE - 1 || across > PROBE_MARK_SIZE + 1) {
                anchor->cut++;
                anchor->faulty = 1;
            }
        }
        if (which != PROBE_MARK_TOP_RIGHT) {
            int32_t expected = which == PROBE_MARK_BOTTOM_LEFT ? calibration->bottom_left_y : calibration->bottom_right_y;
            probe_anchor_take(anchor, probe_shift(border->inner.bottom, expected, mark.bottom), which);
            int32_t across = mark.bottom - mark.top;
            if (across < PROBE_MARK_SIZE - 1 || across > PROBE_MARK_SIZE + 1) {
                anchor->cut++;
                anchor->faulty = 1;
            }
        }
    }
}

enum { PROBE_EDGE_LEFT, PROBE_EDGE_TOP, PROBE_EDGE_RIGHT, PROBE_EDGE_BOTTOM, PROBE_EDGE_INSIDE, PROBE_EDGES };
static const char *const probe_edge_names[PROBE_EDGES] = {"left", "top", "right", "bottom", "inside"};

// Rows and columns the see-through judge reads across the area inside the border, besides
// one just inside each edge. The corner squares this many px wide are left out of the rows
// and columns near an edge (a rounded corner, should DWM still round one).
#define PROBE_SEE_SAMPLES 12
#define PROBE_SEE_CORNER 12

/** The see-through judge's answer for one frame. */
struct probe_see {
    int faulty;
    int32_t px;  // the widest strip of backdrop found, in px
    int edge;  // where it was (PROBE_EDGE_*)
    int32_t total;  // backdrop pixels among those read
    int other_size;  // the size code read is not the border's inner size (with calibration)
    int unreadable;  // the size code did not read
    int covered;  // the size code was not read: the pointer was over it
    int32_t picture_width, picture_height;  // what it read
};

static void probe_see_take(struct probe_see *see, int32_t run, int edge) {
    if (run > see->px) {
        see->px = run;
        see->edge = edge;
    }
}

/**
 * See-through: any backdrop pixel on [PROBE_SEE_SAMPLES] rows and as many columns spread over
 * the area inside the border (and on the row and the column just inside each edge) is the
 * desktop showing through the window. The strip's width is the run of backdrop that touches
 * an edge; backdrop that touches none is counted at "inside". Then the size the picture
 * spells must be the border's inner size (calibrated); a frame whose code does not read is
 * counted apart.
 */
static void probe_judge_see(const struct probe_image *image, const struct probe_border *border,
                            const struct probe_calibration *calibration, struct probe_box pointer,
                            struct probe_see *see) {
    memset(see, 0, sizeof *see);
    see->edge = PROBE_EDGE_INSIDE;
    struct probe_box in = border->inner;
    // Every pixel inside the border: a border pixel DWM blends with what is beside it is
    // blue, never backdrop green, so the first and the last row and column are read too.
    int32_t x0 = in.left, x1 = in.right, y0 = in.top, y1 = in.bottom;
    if (x1 - x0 < 2 * PROBE_SEE_CORNER + 2 || y1 - y0 < 2 * PROBE_SEE_CORNER + 2) return;
    // A row read all backdrop lies in a strip along the top or the bottom (the columns measure
    // it), and a column read all backdrop in one along the left or the right (the rows do).
    int32_t whole = 0;
    for (int axis = 0; axis < 2; axis++) {
        int32_t lo = axis == 0 ? y0 : x0, hi = axis == 0 ? y1 : x1;
        for (int i = 0; i < PROBE_SEE_SAMPLES + 2; i++) {
            int32_t at = i == PROBE_SEE_SAMPLES ? lo
                         : i == PROBE_SEE_SAMPLES + 1 ? hi - 1
                                                      : lo + (int32_t)((int64_t)(hi - 1 - lo) * (i + 1) / (PROBE_SEE_SAMPLES + 1));
            int near_edge = at < lo + PROBE_SEE_CORNER || at >= hi - PROBE_SEE_CORNER;
            int32_t start = axis == 0 ? x0 : y0, end = axis == 0 ? x1 : y1;
            if (near_edge) {
                start += PROBE_SEE_CORNER;
                end -= PROBE_SEE_CORNER;
            }
            int32_t count = 0, run_low = 0, run_high = 0;
            for (int32_t p = start; p < end; p++) {
                const uint8_t *pixel = axis == 0 ? probe_pixel(image, p, at) : probe_pixel(image, at, p);
                if (probe_is_backdrop(pixel)) count++;
            }
            if (count == 0) continue;
            see->total += count;
            if (count == end - start) {
                if (count > whole) whole = count;
                continue;
            }
            while (start + run_low < end &&
                   probe_is_backdrop(axis == 0 ? probe_pixel(image, start + run_low, at) : probe_pixel(image, at, start + run_low))) {
                run_low++;
            }
            while (end - 1 - run_high >= start &&
                   probe_is_backdrop(axis == 0 ? probe_pixel(image, end - 1 - run_high, at)
                                               : probe_pixel(image, at, end - 1 - run_high))) {
                run_high++;
            }
            // The rows and columns just inside an edge, with their corners left out, say only
            // that something shows through; the strip's width is measured across the middle.
            if (!near_edge) {
                probe_see_take(see, run_low, axis == 0 ? PROBE_EDGE_LEFT : PROBE_EDGE_TOP);
                probe_see_take(see, run_high, axis == 0 ? PROBE_EDGE_RIGHT : PROBE_EDGE_BOTTOM);
            }
            int32_t inside = count - run_low - run_high;
            if (inside > 0) probe_see_take(see, inside, PROBE_EDGE_INSIDE);
        }
    }
    if (see->total > 0) {
        see->faulty = 1;
        // Only whole rows and columns: the window shows through everywhere it was read.
        if (see->px == 0) {
            see->px = whole > 0 ? whole : see->total;
            see->edge = PROBE_EDGE_INSIDE;
        }
    }
    // The size code is not read where the pointer covers the top-left marker or the code, in
    // their place or where the marker was found ([pointer], as for the anchor).
    struct probe_box top_left = {0, 0, 0, 0};
    int found = probe_find_mark(image, probe_mark_area(PROBE_MARK_TOP_LEFT, in), PROBE_MARK_TOP_LEFT, &top_left);
    struct probe_box home = {in.left + PROBE_MARK_INSET, in.top + PROBE_MARK_INSET,
                             in.left + PROBE_CODE_X + PROBE_CODE_BLOCKS * PROBE_CODE_BLOCK,
                             in.top + PROBE_CODE_Y + PROBE_CODE_BLOCK};
    struct probe_box code = {top_left.left, top_left.top,
                             top_left.left + (PROBE_CODE_X - PROBE_MARK_INSET) + PROBE_CODE_BLOCKS * PROBE_CODE_BLOCK,
                             top_left.top + (PROBE_CODE_Y - PROBE_MARK_INSET) + PROBE_CODE_BLOCK};
    if (probe_boxes_meet(pointer, probe_grow(home, 8)) || (found && probe_boxes_meet(pointer, probe_grow(code, 8)))) {
        see->covered = 1;
        return;
    }
    if (!found || !probe_read_size(image, top_left, &see->picture_width, &see->picture_height)) {
        see->unreadable = 1;
        see->faulty = 1;
        return;
    }
    if (see->picture_width != in.right - in.left + calibration->extra_width ||
        see->picture_height != in.bottom - in.top + calibration->extra_height) {
        see->other_size = 1;
        see->faulty = 1;
    }
}

/** A fixed-seed generator for the drags' pace and steps, so runs are comparable (xorshift32). */
struct probe_rng {
    uint32_t state;
};

static uint32_t probe_rng_next(struct probe_rng *rng) {
    uint32_t x = rng->state != 0 ? rng->state : 0x9E3779B9u;
    x ^= x << 13;
    x ^= x >> 17;
    x ^= x << 5;
    rng->state = x;
    return x;
}

/** A number from [low] to [high], both included. */
static int32_t probe_rng_between(struct probe_rng *rng, int32_t low, int32_t high) {
    if (high <= low) return low;
    return low + (int32_t)(probe_rng_next(rng) % (uint32_t)(high - low + 1));
}

/**
 * The pointer's offsets along a drag through [targets] (px from where it was pressed,
 * positive grows the window), in steps from [step_low] to [step_high] px; the last step to
 * each target is whatever is left. Writes at most [capacity]; answers how many.
 */
static int probe_path(struct probe_rng *rng, const int32_t *targets, int target_count, int32_t step_low,
                      int32_t step_high, int32_t *out, int capacity) {
    int count = 0;
    int32_t at = 0;
    for (int t = 0; t < target_count; t++) {
        while (at != targets[t] && count < capacity) {
            int32_t step = probe_rng_between(rng, step_low, step_high);
            int32_t left = targets[t] - at;
            if (left > 0) {
                at += step < left ? step : left;
            } else {
                at -= step < -left ? step : -left;
            }
            out[count++] = at;
        }
    }
    return count;
}

#endif
