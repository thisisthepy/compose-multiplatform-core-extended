/*
 * The scale follows Xft.dpi in the root window's RESOURCE_MANAGER, played against
 * x11_window.c itself: read when the window opens (the window is made at its size in points
 * times the scale) and read again when another client rewrites the property, as `xrdb -merge`
 * does, with the window keeping its size in pixels.
 *
 * Needs a display (CI runs it under Xvfb). Exit status 0 means every check passed.
 */
#include "../../c/x11_window.c"

static int failures;

#define CHECK(condition, message) \
    do { if (!(condition)) { fprintf(stderr, "FAIL: %s\n", message); failures++; } } while (0)

/* Another client of the display server, which is what writes the resources on a desktop. */
static Display *peer;

static void set_resources(const char *text) {
    XChangeProperty(peer, DefaultRootWindow(peer), XA_RESOURCE_MANAGER, XA_STRING, 8,
                    PropModeReplace, (const unsigned char *)text, (int)strlen(text));
    XFlush(peer);
}

static float scale_now(int32_t *width, int32_t *height) {
    float scale = 0.0f;
    dxc_native_window_size(NULL, width, height, &scale);
    return scale;
}

/* Pumps until the scale is [wanted], for up to three seconds. */
static int pump_until_scale(float wanted) {
    int32_t width = 0, height = 0;
    for (int i = 0; i < 300; i++) {
        dxc_native_pump(0.01);
        if (scale_now(&width, &height) == wanted) return 1;
    }
    return 0;
}

static void settle(void) {
    for (int i = 0; i < 30; i++) dxc_native_pump(0.01);
    struct dxc_event record;
    while (dxc_native_poll_event(&record)) {}
}

int main(void) {
    if (getenv("GDK_SCALE") != NULL) unsetenv("GDK_SCALE");
    peer = XOpenDisplay(NULL);
    if (peer == NULL) {
        fprintf(stderr, "FAIL: no display\n");
        return 1;
    }
    set_resources("Xft.antialias:\t1\nXft.dpi:\t144\n");

    struct dxc_native_window w;
    if (dxc_native_window_open("scale", 200, 100, &w) != 0) {
        fprintf(stderr, "FAIL: no window\n");
        return 1;
    }
    settle();
    int32_t width = 0, height = 0;
    float scale = scale_now(&width, &height);
    fprintf(stderr, "opened at Xft.dpi 144: %dx%d scale %.2f\n", width, height, scale);
    CHECK(scale == 1.5f, "Xft.dpi 144 at open is not a scale of 1.5");
    CHECK(width == 300 && height == 150, "the window was not made at 200x100 points times 1.5");

    set_resources("Xft.antialias:\t1\nXft.dpi:\t192\n");
    CHECK(pump_until_scale(2.0f), "a change of Xft.dpi to 192 was not read again");
    scale = scale_now(&width, &height);
    fprintf(stderr, "after Xft.dpi 192: %dx%d scale %.2f\n", width, height, scale);
    CHECK(width == 300 && height == 150, "the window changed its size in pixels with the scale");

    set_resources("Xft.antialias:\t1\nXft.dpi:\t96\n");
    CHECK(pump_until_scale(1.0f), "a change of Xft.dpi to 96 was not read again");

    set_resources("Xft.dpi:\t120\n");
    CHECK(pump_until_scale(1.25f), "a change of Xft.dpi to 120 was not read again");

    XDeleteProperty(peer, DefaultRootWindow(peer), XA_RESOURCE_MANAGER);
    XFlush(peer);
    CHECK(pump_until_scale(1.0f), "resources taken away did not go back to a scale of 1");

    dxc_native_window_action(2);
    dxc_native_pump(0.0);
    XCloseDisplay(peer);
    if (failures == 0) fprintf(stderr, "scale_test: all checks passed\n");
    return failures == 0 ? 0 : 1;
}
