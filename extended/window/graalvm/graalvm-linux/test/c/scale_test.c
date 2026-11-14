/*
 * The scale follows Xft.dpi in the root window's RESOURCE_MANAGER, played against
 * x11_window.c itself: read when the window opens (the window is made at its size in points
 * times the scale) and read again when another client rewrites the property, as `xrdb -merge`
 * does, with the window keeping its size in points (FR-82): 640x480 pixels at Xft.dpi 96 are
 * 1280x960 at 192, and the minimum size is told again in the new pixels. GNOME's
 * text-scaling-factor and KDE's forced font DPI, which those desktops also write into Xft.dpi,
 * are taken out of the scale.
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

/* The size the server holds the window at, after it has had the time to take a resize. */
static void server_size(int32_t *width, int32_t *height) {
    for (int i = 0; i < 30; i++) dxc_native_pump(0.01);
    dxc_native_window_server_size(width, height);
}

/* The smallest size the window tells the manager, in pixels, as another client reads it. */
static int min_hint(int *width, int *height) {
    XSizeHints hints;
    long supplied = 0;
    memset(&hints, 0, sizeof hints);
    if (!XGetWMNormalHints(peer, dxc_window, &hints, &supplied) || !(hints.flags & PMinSize)) return 0;
    *width = hints.min_width;
    *height = hints.min_height;
    return 1;
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
    if (dxc_native_window_open("scale", 640, 480, &w) != 0) {
        fprintf(stderr, "FAIL: no window\n");
        return 1;
    }
    settle();
    int32_t width = 0, height = 0;
    float scale = scale_now(&width, &height);
    fprintf(stderr, "opened at Xft.dpi 144: %dx%d scale %.2f\n", width, height, scale);
    CHECK(scale == 1.5f, "Xft.dpi 144 at open is not a scale of 1.5");
    CHECK(width == 960 && height == 720, "the window was not made at 640x480 points times 1.5");

    set_resources("Xft.antialias:\t1\nXft.dpi:\t96\n");
    CHECK(pump_until_scale(1.0f), "a change of Xft.dpi to 96 was not read again");
    scale = scale_now(&width, &height);
    CHECK(width == 640 && height == 480, "at Xft.dpi 96 the window is not 640x480 pixels");
    server_size(&width, &height);
    fprintf(stderr, "at Xft.dpi 96: the server holds %dx%d\n", width, height);
    CHECK(width == 640 && height == 480, "the server does not hold the window at 640x480 at Xft.dpi 96");
    dxc_native_set_min_size(160, 120);
    int min_width = 0, min_height = 0;
    CHECK(min_hint(&min_width, &min_height) && min_width == 160 && min_height == 120,
          "a minimum of 160x120 points is not 160x120 pixels at Xft.dpi 96");

    // FR-82: the window keeps its size in points, so it is twice the pixels at twice the scale.
    set_resources("Xft.antialias:\t1\nXft.dpi:\t192\n");
    CHECK(pump_until_scale(2.0f), "a change of Xft.dpi to 192 was not read again");
    scale = scale_now(&width, &height);
    fprintf(stderr, "after Xft.dpi 192: %dx%d scale %.2f\n", width, height, scale);
    CHECK(width == 1280 && height == 960, "640x480 pixels at Xft.dpi 96 did not measure 1280x960 at 192");
    server_size(&width, &height);
    fprintf(stderr, "after Xft.dpi 192: the server holds %dx%d\n", width, height);
    CHECK(width == 1280 && height == 960, "640x480 pixels at Xft.dpi 96 were not resized to 1280x960 at 192");
    scale = scale_now(&width, &height);
    CHECK(width == 1280 && height == 960, "the server's answer to the resize changed the measurement");
    CHECK(min_hint(&min_width, &min_height) && min_width == 320 && min_height == 240,
          "a minimum of 160x120 points is not told again as 320x240 pixels at Xft.dpi 192");
    dxc_native_set_min_size(0, 0);

    set_resources("Xft.antialias:\t1\nXft.dpi:\t96\n");
    CHECK(pump_until_scale(1.0f), "a change of Xft.dpi to 96 was not read again");
    server_size(&width, &height);
    CHECK(width == 640 && height == 480, "1280x960 pixels at Xft.dpi 192 were not resized to 640x480 at 96");

    set_resources("Xft.dpi:\t120\n");
    CHECK(pump_until_scale(1.25f), "a change of Xft.dpi to 120 was not read again");
    server_size(&width, &height);
    CHECK(width == 800 && height == 600, "640x480 pixels at Xft.dpi 96 were not resized to 800x600 at 120");
    set_resources("Xft.dpi:\t240\n");
    CHECK(pump_until_scale(2.5f), "a change of Xft.dpi to 240 was not read again");
    server_size(&width, &height);
    CHECK(width == 1600 && height == 1200, "800x600 pixels at a scale of 1.25 were not resized to 1600x1200 at 2.5");
    set_resources("Xft.dpi:\t120\n");
    CHECK(pump_until_scale(1.25f), "a change of Xft.dpi back to 120 was not read again");

    // GNOME writes Xft.dpi as 96 x its window scale x text-scaling-factor; the factor is the
    // text scale, so the window scale is what is left (X11Scale.withoutTextScale).
    dxc_native_set_text_factor(1.25f);
    CHECK(scale_now(&width, &height) == 1.0f,
          "Xft.dpi 120 with a GNOME text factor of 1.25 is not a scale of 1 at once");
    set_resources("Xft.antialias:\t1\nXft.dpi:\t240\n");
    CHECK(pump_until_scale(2.0f), "Xft.dpi 240 with a text factor of 1.25 is not a scale of 2");
    set_resources("Xft.dpi:\t144\n");
    CHECK(pump_until_scale(1.5f), "Xft.dpi 144, no multiple of 120, did not stay a scale of 1.5");
    set_resources("Xft.dpi:\t240\n");
    CHECK(pump_until_scale(2.0f), "Xft.dpi 240 with a text factor of 1.25 is not a scale of 2 again");
    dxc_native_set_text_factor(0.0f);
    CHECK(scale_now(&width, &height) == 2.5f, "no text factor left Xft.dpi 240 short of a scale of 2.5");
    // 96 x 2 x 1.1 is 211.2, which GNOME writes as 211.
    dxc_native_set_text_factor(1.1f);
    set_resources("Xft.dpi:\t211\n");
    CHECK(pump_until_scale(2.0f), "Xft.dpi 211 with a text factor of 1.1 is not a scale of 2");
    dxc_native_set_text_factor(1.0f);
    CHECK(scale_now(&width, &height) > 2.19f && scale_now(&width, &height) < 2.2f,
          "a text factor of 1 took something out of Xft.dpi 211");
    dxc_native_set_text_factor(0.0f);

    // KDE writes its forced font DPI into Xft.dpi: 96 x the global scale x the text size, the
    // global scale named in kdeglobals, fractional ones too.
    dxc_native_set_text_factor_for(1.25f, 1.0f);
    set_resources("Xft.dpi:\t120\n");
    CHECK(pump_until_scale(1.0f), "KDE at 100% with fonts forced to 120 DPI is not a scale of 1");
    dxc_native_set_text_factor_for(1.25f, 2.0f);
    set_resources("Xft.dpi:\t240\n");
    CHECK(pump_until_scale(2.0f), "KDE at 200% with fonts forced to 240 DPI is not a scale of 2");
    dxc_native_set_text_factor_for(1.25f, 1.5f);
    set_resources("Xft.dpi:\t180\n");
    CHECK(pump_until_scale(1.5f), "KDE at 150% with fonts forced to 180 DPI is not a scale of 1.5");
    set_resources("Xft.dpi:\t144\n");
    CHECK(pump_until_scale(1.5f), "KDE at 150% with Xft.dpi 144 is not a scale of 1.5");
    set_resources("Xft.dpi:\t200\n");
    CHECK(pump_until_scale((float)(200.0 / 96.0)), "Xft.dpi 200, not from KDE's forced DPI, was not left alone");
    dxc_native_set_text_factor_for(0.0f, 0.0f);

    XDeleteProperty(peer, DefaultRootWindow(peer), XA_RESOURCE_MANAGER);
    XFlush(peer);
    CHECK(pump_until_scale(1.0f), "resources taken away did not go back to a scale of 1");

    dxc_native_window_action(2);
    dxc_native_pump(0.0);
    XCloseDisplay(peer);
    if (failures == 0) fprintf(stderr, "scale_test: all checks passed\n");
    return failures == 0 ? 0 : 1;
}
