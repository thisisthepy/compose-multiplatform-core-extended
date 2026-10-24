/*
 * A resize the way a person does it, through a window manager that holds each size until the
 * client has drawn it: openbox, which waits on the _NET_WM_SYNC_REQUEST counter during an
 * interactive resize, drags the window's corner (scripts/wm-drag.sh), and every frame drawn
 * is compared with the size the server has at that moment.
 *
 * No frame may be drawn at a size the server has already left (stale), and no size the
 * server confirmed may be left without a frame of its own (stretched). Needs a display, and
 * WM_DRAG_SCRIPT naming scripts/wm-drag.sh (CI sets both; openbox and xdotool installed).
 */
#include "../../c/x11_window.c"

#include <pthread.h>
#include <stdatomic.h>

static int failures;

#define CHECK(condition, message) \
    do { if (!(condition)) { fprintf(stderr, "FAIL: %s\n", message); failures++; } } while (0)

static int frames;
static int stale;
static int64_t last_painted_serial;
static int64_t stretched;
static atomic_int dragged;
static int drag_status = -1;

static int32_t paint(void *thread) {
    (void)thread;
    if (dxc_native_frame_begin(NULL) != 0) return 0;
    glClearColor(0.2f, 0.4f, 0.6f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    XWindowAttributes attributes;
    XGetWindowAttributes(dxc_display, dxc_window, &attributes);
    frames++;
    if (attributes.width != dxc_width || attributes.height != dxc_height) {
        stale++;
        fprintf(stderr, "stale: drawn %dx%d, the server has %dx%d\n", dxc_width, dxc_height,
                attributes.width, attributes.height);
    }
    if (dxc_resize_serial > last_painted_serial + 1) {
        stretched += dxc_resize_serial - last_painted_serial - 1;
    }
    last_painted_serial = dxc_resize_serial;
    /* The swap, then the counter the window manager is waiting on. */
    dxc_native_frame_end(NULL);
    return 1;
}

static void *drag(void *script) {
    char command[4096];
    snprintf(command, sizeof command, "'%s' 'wm drag'", (const char *)script);
    drag_status = system(command);
    atomic_store(&dragged, 1);
    return NULL;
}

static double now_seconds(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (double)t.tv_sec + (double)t.tv_nsec / 1e9;
}

int main(void) {
    const char *script = getenv("WM_DRAG_SCRIPT");
    if (script == NULL || script[0] == 0) {
        fprintf(stderr, "skip: WM_DRAG_SCRIPT is not set\n");
        return getenv("CI") != NULL ? 1 : 0;
    }
    dxc_native_window_configure(1, 0, 0, 1, 0);
    struct dxc_native_window w;
    if (dxc_native_window_open("wm drag", 320, 240, &w) != 0) {
        fprintf(stderr, "FAIL: no window\n");
        return 1;
    }
    dxc_native_set_frame_callback(paint, NULL);
    for (int i = 0; i < 30; i++) dxc_native_pump(0.01);
    int64_t serial_before = dxc_resize_serial;
    last_painted_serial = dxc_resize_serial;
    frames = 0;
    stale = 0;
    stretched = 0;

    pthread_t driver;
    pthread_create(&driver, NULL, drag, (void *)script);
    double started = now_seconds();
    while (!atomic_load(&dragged) && now_seconds() - started < 60.0) dxc_native_pump(0.016);
    for (int i = 0; i < 30; i++) dxc_native_pump(0.016);
    pthread_join(driver, NULL);
    if (dxc_resize_serial > last_painted_serial) stretched += dxc_resize_serial - last_painted_serial;

    int64_t sizes = dxc_resize_serial - serial_before;
    printf("graalvm x11 resize through openbox: sizes=%lld frames=%d stale=%d stretched=%lld\n",
           (long long)sizes, frames, stale, (long long)stretched);
    CHECK(drag_status == 0, "the drag script failed");
    CHECK(sizes >= 10, "the window manager took the window through fewer than ten sizes");
    CHECK(stale == 0, "a frame was drawn at a size the server had already left");
    CHECK(stretched == 0, "a size the server confirmed was left without a frame of its own");
    dxc_native_set_frame_callback(NULL, NULL);
    dxc_native_window_action(2);
    dxc_native_pump(0.0);
    return failures == 0 ? 0 : 1;
}
