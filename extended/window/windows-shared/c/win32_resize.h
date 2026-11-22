#ifndef DXC_WIN32_RESIZE_H
#define DXC_WIN32_RESIZE_H

#include <stdint.h>

// What the window knows about its own size, and about being dragged by an edge.
//
// Kept apart from the window because none of it needs one. Every decision here is
// arithmetic on five numbers, and a machine with no Windows on it can still compile this
// header and check that the arithmetic says what it should. What the answers lead to, a
// swapchain refitted and a frame drawn, is the only part that needs the platform.

/**
 * The size the window has been given, the size it is drawn at, and whether a drag is on.
 *
 * The two sizes are apart because they disagree for as long as it takes to refit the
 * swapchain, and because a refit can be refused: the size a frame is drawn at is the one
 * the swapchain was actually made, never the one that was asked for.
 */
struct dxc_resize {
    // The size the client area was last reported to be, in pixels, waiting for a frame
    // to fit the swapchain to it. Zero once it has been taken.
    int32_t wanted_width;
    int32_t wanted_height;
    // The size the swapchain was last made, which is the size a frame is drawn at.
    int32_t fitted_width;
    int32_t fitted_height;
    // Between the reader taking hold of the window's edge and letting go of it. Windows
    // runs a loop of its own for the whole of that, inside the handler for the press
    // that began it, so the renderer's frame loop gets no turn from the first press to
    // the last release.
    int dragging;
    // Refits refused in a row, since the last one that succeeded or the last size the
    // window was given.
    int32_t refusals;
};

// How many frames in a row ask again for a size the swapchain refused, before the window
// waits for the next size it is given. Enough for a refusal that passes (a buffer still
// held for a moment); few enough that one that does not pass is not a frame drawn and
// refused for ever.
#define DXC_RESIZE_RETRIES 3

/** Writes down a size the window has just been given. */
static void dxc_resize_note(struct dxc_resize *resize, int32_t width, int32_t height) {
    resize->wanted_width = width;
    resize->wanted_height = height;
    resize->refusals = 0;
}

/**
 * Takes the size the next frame has to be drawn at.
 *
 * Zero when there is nothing to do, which is the ordinary answer: a window that is not
 * being resized has been given no size, and a window that has just been shown reports the
 * size its swapchain was already made at. The note is cleared either way, because a size
 * that matches what is already fitted has been dealt with by being read.
 */
static int dxc_resize_take(struct dxc_resize *resize, int32_t *width, int32_t *height) {
    int32_t wanted_width = resize->wanted_width;
    int32_t wanted_height = resize->wanted_height;
    if (wanted_width <= 0 || wanted_height <= 0) {
        return 0;
    }
    resize->wanted_width = 0;
    resize->wanted_height = 0;
    if (wanted_width == resize->fitted_width && wanted_height == resize->fitted_height) {
        return 0;
    }
    *width = wanted_width;
    *height = wanted_height;
    return 1;
}

/**
 * Records the size the swapchain was made.
 *
 * Called where a refit succeeded and where the swapchain was first created, and nowhere
 * else. A refusal leaves this as it was, so the window carries on drawing at the size it
 * has rather than at the size it was denied.
 */
static void dxc_resize_fitted(struct dxc_resize *resize, int32_t width, int32_t height) {
    resize->fitted_width = width;
    resize->fitted_height = height;
    resize->refusals = 0;
}

/**
 * A refit to [width] by [height] was refused, which leaves the swapchain the size it was.
 *
 * The swapchain's buffers are then the old size, and DXGI_SCALING_STRETCH shows them
 * stretched over the new client area for as long as nothing presents again. So the size is
 * written down again for the next frame to ask for, and the answer says whether to ask for
 * that frame now: one for the first DXC_RESIZE_RETRIES refusals in a row, then zero, and the
 * window waits for the next size it is given. A size that arrived meanwhile is kept.
 */
static int dxc_resize_refused(struct dxc_resize *resize, int32_t width, int32_t height) {
    if (resize->refusals >= DXC_RESIZE_RETRIES) {
        return 0;
    }
    resize->refusals++;
    if (resize->wanted_width <= 0 || resize->wanted_height <= 0) {
        resize->wanted_width = width;
        resize->wanted_height = height;
    }
    return 1;
}

/** The reader has taken hold of the window's edge. */
static void dxc_resize_begin_drag(struct dxc_resize *resize) {
    resize->dragging = 1;
}

/** The reader has let go of it. */
static void dxc_resize_end_drag(struct dxc_resize *resize) {
    resize->dragging = 0;
}

/**
 * Whether a size arriving now has to be drawn where it arrives.
 *
 * True inside a drag and false outside one. Outside, writing the size down is enough: the
 * frame loop is running and the next frame takes it. Inside, there is no next frame until
 * the reader lets go, so a size left for the frame loop is a size nothing draws, and what
 * the screen shows for the length of the drag is the last frame stretched to fit.
 */
static int dxc_resize_draw_here(const struct dxc_resize *resize) {
    return resize->dragging;
}

/**
 * Whether a size the window is about to be given has to be drawn before it is given.
 *
 * Asked from WM_NCCALCSIZE, which knows the new client size before the window has it. A
 * size given outside a drag (maximise, restore, a snap, any SetWindowPos) is applied to
 * the window first and reaches WM_SIZE only afterwards, and whatever WM_SIZE then draws
 * takes as long as a frame takes. For that long DWM can compose the new rectangle with
 * the picture drawn for the old one: the swapchain's child window keeps its old size and
 * is cut by the new rectangle, or the window's own surface keeps its old pixels in the
 * top left. Drawing the frame here, before the rectangle changes, leaves WM_SIZE only the
 * copy, which is short enough to land before DWM composes again.
 *
 * Only where the frame can be drawn on the CPU (raster: the renderer draws resize frames
 * into the bitmap the window copies), only outside a drag (the drag draws in WM_SIZE, and
 * a draw here would wait one composition per step), never while a frame is already being
 * drawn, never for an empty client area (minimised), and only for a size that differs
 * from the one the window has now and from the one last shown.
 */
static int dxc_resize_predraw_wanted(const struct dxc_resize *resize, int raster, int drawing,
                                     int32_t width, int32_t height, int32_t current_width,
                                     int32_t current_height, int32_t shown_width,
                                     int32_t shown_height) {
    if (!raster || drawing || resize->dragging || width <= 0 || height <= 0) {
        return 0;
    }
    if (width == current_width && height == current_height) {
        return 0;
    }
    return width != shown_width || height != shown_height;
}

/**
 * Whether a step of a drag has to be drawn before the window takes the step's size.
 *
 * Asked from WM_NCCALCSIZE too, inside a drag. A step drawn in WM_SIZE is drawn after the
 * window has the new rectangle, and the draw takes several milliseconds (4 to 6 on a CI
 * runner). A composition that falls in that time shows the new rectangle with the picture
 * of the step before: where the window got smaller, the old picture cut by the new
 * rectangle, its right or bottom edge missing (issue 245; 26 of the 29 faulty frames of one
 * run were composed between WM_SIZE and the copy). Drawn here instead, into the bitmap,
 * while the window still has the old rectangle and the old picture, the step leaves WM_SIZE
 * only the copy, a fraction of a millisecond.
 *
 * Only inside a drag, only with CPU frames, never while a frame is being drawn, never for
 * an empty client area, only for a size that differs from the one the window has, and not
 * where it was turned off ([enabled], DXC_DRAG_PREDRAW=0).
 */
static int dxc_resize_drag_predraw_wanted(const struct dxc_resize *resize, int raster, int drawing,
                                          int enabled, int32_t width, int32_t height,
                                          int32_t current_width, int32_t current_height) {
    if (!enabled || !raster || drawing || !resize->dragging || width <= 0 || height <= 0) {
        return 0;
    }
    return width != current_width || height != current_height;
}

// The first build of the Windows 11 generation of the desktop compositor. Server 2022
// (20348) and every Windows 10 are below it; Windows 11 and Server 2025 (26100) are not.
#define DXC_WINDOWS_11_BUILD 22000

/**
 * Whether a step of a drag drawn ahead waits for one composition before the window takes
 * its size, so the size change and the copy that follows it start a composition interval
 * together rather than straddle one.
 *
 * The wait costs up to one composition per step, which is the pace the GPU path has. It is
 * taken on every build. It was once taken only on the Windows 10 generation of the
 * compositor (build below DXC_WINDOWS_11_BUILD), where 15 of 24 drags showed the fault, but
 * the Windows 11 generation shows it too, in 1 of 24 drags (a shrinking step of a right-edge
 * drag on windows-2025, compose-rust#926), and a picture cut by its window is worse than a
 * slower step (owner, 2026-11-22). [asked] 0 never waits (DXC_DRAG_FLUSH=0, for comparing);
 * anything else waits. [build] is kept for the log line and no longer decides.
 */
static int dxc_resize_drag_flush_wanted(int asked, uint32_t build) {
    (void)build;
    return asked != 0;
}

/**
 * A frame drawn ahead of a size change and waiting for the size to arrive, or nothing
 * (both zero).
 */
struct dxc_predrawn {
    int32_t width;
    int32_t height;
};

/** Writes down that a frame of [width] by [height] is drawn and waiting to be shown. */
static void dxc_predrawn_set(struct dxc_predrawn *predrawn, int32_t width, int32_t height) {
    predrawn->width = width;
    predrawn->height = height;
}

/**
 * Takes the waiting frame for a size that has just arrived: one when it was drawn at
 * exactly that size, so it is shown as it is, and zero otherwise, so a frame is drawn for
 * the size as usual. The waiting frame is gone either way: a frame drawn for a size the
 * window did not get is a frame nobody may show.
 */
static int dxc_predrawn_take(struct dxc_predrawn *predrawn, int32_t width, int32_t height) {
    int match = predrawn->width > 0 && predrawn->height > 0 && predrawn->width == width &&
                predrawn->height == height;
    predrawn->width = 0;
    predrawn->height = 0;
    return match;
}

#endif
