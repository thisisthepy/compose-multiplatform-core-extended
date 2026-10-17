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
