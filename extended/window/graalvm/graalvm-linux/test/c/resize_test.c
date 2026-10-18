/*
 * The scripted drag the parity check measures with, played against x11_window.c itself:
 * dxc_native_debug_resize takes the window through the sizes, each one is counted in
 * dxc_native_resize_serial, and the server's answer for the size is the last one asked.
 *
 * Needs a display (CI runs it under Xvfb, where no window manager stands between the request
 * and the size). Exit status 0 means every check passed.
 */
#include "../../c/x11_window.c"

static int failures;

#define CHECK(condition, message) \
    do { if (!(condition)) { fprintf(stderr, "FAIL: %s\n", message); failures++; } } while (0)

/*
 * A stand-in for the renderer's frame painter: called where the window draws a frame, it notes
 * the size the frame would be drawn at and the size the server has at that moment. They must
 * be the same: a frame drawn at a size the server has already left is a stale frame.
 */
static int frames;
static int stale_frames;

static int32_t paint(void *thread) {
    (void)thread;
    XWindowAttributes attributes;
    if (XGetWindowAttributes(dxc_display, dxc_window, &attributes)) {
        frames++;
        if (attributes.width != dxc_width || attributes.height != dxc_height) {
            stale_frames++;
            fprintf(stderr, "stale: drawn %dx%d, the server has %dx%d\n", dxc_width, dxc_height,
                    attributes.width, attributes.height);
        }
    }
    return 1;
}

int main(void) {
    struct dxc_native_window w;
    if (dxc_native_window_open("resize", 320, 240, &w) != 0) {
        fprintf(stderr, "FAIL: no window\n");
        return 1;
    }
    for (int i = 0; i < 30; i++) dxc_native_pump(0.01);

    int64_t before = dxc_native_resize_serial();
    int32_t width = 0, height = 0;
    dxc_native_window_server_size(&width, &height);
    dxc_native_debug_resize(NULL, NULL, width, height, 360, 420, 10, 5000);
    for (int i = 0; i < 10; i++) dxc_native_pump(0.01);
    int64_t after = dxc_native_resize_serial();
    fprintf(stderr, "sizes taken: %lld\n", (long long)(after - before));
    CHECK(after - before >= 1, "the scripted drag took the window through no sizes");
    CHECK(after - before <= 10, "more sizes were counted than were asked for");

    dxc_native_window_server_size(&width, &height);
    fprintf(stderr, "server says %dx%d\n", width, height);
    CHECK(width == 360 && height == 420, "the window did not end at the size the drag ended at");

    /*
     * A drag while work keeps arriving from another thread, which ends every wait in the pump
     * at once: the drag must still ask for the next size only after this one was confirmed
     * and drawn, so no frame is drawn at a size the server has already left.
     */
    dxc_native_set_frame_callback(paint, NULL);
    dxc_native_window_server_size(&width, &height);
    for (int i = 0; i < 4; i++) dxc_native_wake();
    dxc_native_debug_resize(NULL, NULL, width, height, 300, 360, 30, 0);
    for (int i = 0; i < 10; i++) dxc_native_pump(0.01);
    fprintf(stderr, "frames drawn during a drag with wakes: %d, stale: %d\n", frames, stale_frames);
    CHECK(frames >= 1, "the drag drew no frames");
    CHECK(stale_frames == 0, "a frame was drawn at a size the server had already left");
    dxc_native_window_server_size(&width, &height);
    CHECK(width == 300 && height == 360, "the drag with wakes did not end at its size");
    dxc_native_set_frame_callback(NULL, NULL);

    /* No steps, no window change. */
    int64_t still = dxc_native_resize_serial();
    dxc_native_debug_resize(NULL, NULL, 360, 420, 100, 100, 0, 0);
    for (int i = 0; i < 5; i++) dxc_native_pump(0.01);
    CHECK(dxc_native_resize_serial() == still, "a drag of no steps changed the size");

    dxc_native_window_action(2);
    dxc_native_pump(0.0);
    if (failures == 0) fprintf(stderr, "resize_test: all checks passed\n");
    return failures == 0 ? 0 : 1;
}
