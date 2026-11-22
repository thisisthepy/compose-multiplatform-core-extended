#ifndef DXC_WIN32_NATIVE_VIEWS_H
#define DXC_WIN32_NATIVE_VIEWS_H

#include <stdint.h>

// The arithmetic and the focus bookkeeping of the window's native view slots
// (common's NativeViews, compose-rust FR-81): platform windows the application made, an HWND
// such as a web view's, placed in the window above the scene.
//
// A slot on Windows is two child windows. The caller's view sits inside a container the
// window owns, which is a child of the top-level window. The container is the part of the
// slot that shows: the slot's frame cut to its clip. The view inside keeps the slot's whole
// frame, moved by how far the clip cut into the frame's top left, so a view scrolled half out
// of its scroll container shows its lower half and not its upper half squeezed. The holes (the
// Compose surfaces drawn above the view: a menu, a popover, a dialog) are cut out of the
// container with a window region, which also sends a click in a hole to the scene below. This
// is the shape of the macOS slot (MacosNativeViews: a container view cut to the clip, its
// layer masked by the holes).
//
// Everything is in the pixels of the window's client area, which is what the scene measures
// in on Windows (the process is per-monitor DPI aware, so a scene pixel is a device pixel).
// There is no conversion of units here, and none at a change of DPI: the window's size and
// the scene's density change, Compose lays out again, and the caller sends the new frames,
// which land here as pixels like the old ones.
//
// Kept apart from the window so the shared test checks it on any machine; it compiles
// without Windows.

/** A rectangle in client pixels: [x], [y] its top left, [width] by [height] pixels. */
struct dxc_nv_rect {
    int32_t x;
    int32_t y;
    int32_t width;
    int32_t height;
};

static inline struct dxc_nv_rect dxc_nv_rect_of(int32_t x, int32_t y, int32_t width, int32_t height) {
    struct dxc_nv_rect rect;
    rect.x = x;
    rect.y = y;
    rect.width = width > 0 ? width : 0;
    rect.height = height > 0 ? height : 0;
    return rect;
}

static inline int dxc_nv_rect_empty(struct dxc_nv_rect rect) {
    return rect.width <= 0 || rect.height <= 0;
}

static inline int dxc_nv_rect_equal(struct dxc_nv_rect a, struct dxc_nv_rect b) {
    return a.x == b.x && a.y == b.y && a.width == b.width && a.height == b.height;
}

/** The part [a] and [b] share, empty (at the right place, with no size) when they do not meet. */
static inline struct dxc_nv_rect dxc_nv_intersect(struct dxc_nv_rect a, struct dxc_nv_rect b) {
    int32_t left = a.x > b.x ? a.x : b.x;
    int32_t top = a.y > b.y ? a.y : b.y;
    int32_t a_right = a.x + a.width, b_right = b.x + b.width;
    int32_t a_bottom = a.y + a.height, b_bottom = b.y + b.height;
    int32_t right = a_right < b_right ? a_right : b_right;
    int32_t bottom = a_bottom < b_bottom ? a_bottom : b_bottom;
    return dxc_nv_rect_of(left, top, right - left, bottom - top);
}

/**
 * The part of a slot that shows, where its container goes: the frame, within the clip when
 * there is one ([has_clip]). The same rule as common's NativeViewRegion.bounds.
 *
 * Not cut to the client area. A child window is cut to its parent's client area by Windows
 * itself, and a slot placed past the right edge of a window that is being made wider shows
 * as the edge uncovers it, with no call from the resize path.
 */
static inline struct dxc_nv_rect dxc_nv_bounds(struct dxc_nv_rect frame, int has_clip, struct dxc_nv_rect clip) {
    return has_clip ? dxc_nv_intersect(frame, clip) : frame;
}

/**
 * Where the caller's view goes inside its container: the whole frame, moved by where the
 * container begins. A clip that cut the frame's left or top puts the view's origin above or
 * left of the container's, so the part that shows is the part the clip leaves.
 */
static inline struct dxc_nv_rect dxc_nv_view_in_container(struct dxc_nv_rect frame, struct dxc_nv_rect bounds) {
    return dxc_nv_rect_of(frame.x - bounds.x, frame.y - bounds.y, frame.width, frame.height);
}

/** Whether a slot's container is shown: the caller wants it shown and some of it is left. */
static inline int dxc_nv_shows(int visible, struct dxc_nv_rect bounds) {
    return visible && !dxc_nv_rect_empty(bounds);
}

/**
 * A hole in the container's own coordinates: [hole] moved to [bounds]' top left. 1 when it
 * cuts into the container at all, and only then is it cut; 0 for a hole beside it.
 */
static inline int dxc_nv_hole_in_container(struct dxc_nv_rect hole, struct dxc_nv_rect bounds,
                                           struct dxc_nv_rect *out) {
    if (dxc_nv_rect_empty(hole) || dxc_nv_rect_empty(dxc_nv_intersect(hole, bounds))) return 0;
    *out = dxc_nv_rect_of(hole.x - bounds.x, hole.y - bounds.y, hole.width, hole.height);
    return 1;
}

/**
 * The radius a hole's corners are cut to: never more than half its shorter side, never less
 * than 0. The rule of common's PixelRoundRect.radius.
 */
static inline int32_t dxc_nv_hole_radius(struct dxc_nv_rect hole, int32_t corner) {
    int32_t shorter = hole.width < hole.height ? hole.width : hole.height;
    int32_t most = shorter / 2;
    if (corner < 0) return 0;
    return corner > most ? most : corner;
}

/**
 * The box CreateRoundRectRgn is given for a hole with rounded corners: one pixel more to the
 * right and to the bottom than the hole. CreateRectRgn leaves out a rectangle's right and
 * bottom edges, which is the hole exactly; CreateRoundRectRgn leaves out one pixel more, and
 * a hole a pixel short of its surface would leave a line of the view over the surface's edge.
 * A pixel too many shows the scene a pixel further under the surface's shadow, which the
 * caller sized the hole to take in anyway. A hole with square corners takes the hole as it is.
 */
static inline struct dxc_nv_rect dxc_nv_round_region_box(struct dxc_nv_rect hole, int32_t radius) {
    if (radius <= 0) return hole;
    return dxc_nv_rect_of(hole.x, hole.y, hole.width + 1, hole.height + 1);
}

// Which slot has keyboard focus, and what to report: common's NativeViewFocus, in C, because
// on Windows the window reports focus through its own event queue (DXC_EVENT_NATIVE_VIEW_FOCUS_IN
// and _OUT, the slot id in key_code) and the system tells the C side, not the Kotlin one.
//
// The window says what the system did: the focus moved to a window inside a slot or out of
// every slot (to the scene), or the top-level window was activated or deactivated. While it is
// not active nothing is reported, and the slot that had focus is remembered: the window says
// it lost it when deactivated and has it again when activated.

struct dxc_nv_focus {
    int32_t is_key;
    int32_t has_focused;
    int32_t focused;
};

/** One focus report: [in] 1 for a slot taking focus, 0 for one losing it; [id] the slot. */
struct dxc_nv_focus_event {
    int32_t in;
    int32_t id;
};

/**
 * The focus moved to slot [id] ([has] 1) or to the scene ([has] 0). Writes the reports, at
 * most two (the slot losing it, then the slot taking it), into [out] and answers how many.
 */
static inline int dxc_nv_focus_moved(struct dxc_nv_focus *focus, int has, int32_t id,
                                     struct dxc_nv_focus_event out[2]) {
    if (focus->has_focused == (has != 0) && (!has || focus->focused == id)) return 0;
    int had = focus->has_focused;
    int32_t before = focus->focused;
    focus->has_focused = has != 0;
    focus->focused = has ? id : 0;
    if (!focus->is_key) return 0;
    int count = 0;
    if (had) {
        out[count].in = 0;
        out[count].id = before;
        count++;
    }
    if (has) {
        out[count].in = 1;
        out[count].id = id;
        count++;
    }
    return count;
}

/** The window was activated ([key] 1) or deactivated. Writes at most one report into [out]. */
static inline int dxc_nv_focus_key(struct dxc_nv_focus *focus, int key, struct dxc_nv_focus_event out[1]) {
    if ((key != 0) == (focus->is_key != 0)) return 0;
    focus->is_key = key != 0;
    if (!focus->has_focused) return 0;
    out[0].in = focus->is_key;
    out[0].id = focus->focused;
    return 1;
}

#endif
