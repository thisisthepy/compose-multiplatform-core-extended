/*
 * The window's chrome, driven the way a person drives it, through openbox
 * (scripts/wm-chrome.sh, the same script the Kotlin/Native window's test runs).
 *
 * Usage: chrome_test <modern|system>
 *
 * The loop here does what the Kotlin layer does with a press the scene hands back: within EDGE
 * pixels of an edge it waits for the pointer to go and then hands the resize to the manager
 * (dxc_native_window_begin_drag_at, from the press), elsewhere in the caption it does the same
 * for the move, and a second press in the caption soon after the first maximises or restores
 * (dxc_native_window_action). The script checks the frame, the caption drag, every edge and
 * corner, and the double click. Every frame drawn on the way is compared with the size the
 * server has at that moment, as wm_resize_test does.
 *
 * Needs a display and WM_CHROME_SCRIPT naming scripts/wm-chrome.sh (CI sets both; openbox,
 * xdotool and xprop installed).
 */
#include "../../c/x11_window.c"

#include <pthread.h>
#include <math.h>
#include <stdatomic.h>

#define EDGE 8
#define CAPTION 46
#define THRESHOLD 4.0f
#define DOUBLE_CLICK_SECONDS 0.4
#define DOUBLE_CLICK_DISTANCE 5.0f

static int failures;

#define CHECK(condition, message) \
    do { if (!(condition)) { fprintf(stderr, "FAIL: %s\n", message); failures++; } } while (0)

static int frames;
static int stale;
static int64_t last_painted_serial;
static int64_t stretched;
static atomic_int driven;
static int drive_status = -1;
static char command[4096];

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
    dxc_native_frame_end(NULL);
    return 1;
}

static void *drive(void *unused) {
    (void)unused;
    drive_status = system(command);
    atomic_store(&driven, 1);
    return NULL;
}

static double now_seconds(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (double)t.tv_sec + (double)t.tv_nsec / 1e9;
}

/* dxc_native_window_begin_drag's number for a press at (x, y), or -1 for none. */
static int edge_at(float x, float y) {
    int left = x < EDGE, right = x >= dxc_width - EDGE;
    int top = y < EDGE, bottom = y >= dxc_height - EDGE;
    if (top && left) return 5;
    if (top && right) return 6;
    if (bottom && left) return 7;
    if (bottom && right) return 8;
    if (left) return 1;
    if (right) return 2;
    if (top) return 3;
    if (bottom) return 4;
    return -1;
}

static int pending = -1;
static float press_x, press_y;
static double last_caption_press = -10.0;
static float last_caption_x, last_caption_y;

static void heard(const struct dxc_event *event) {
    switch (event->kind) {
    case DXC_EVENT_POINTER_DOWN: {
        if (!(event->buttons & 1)) return;
        press_x = event->x;
        press_y = event->y;
        int edge = edge_at(event->x, event->y);
        if (edge >= 0) {
            pending = edge;
            last_caption_press = -10.0;
        } else if (event->y < CAPTION) {
            double now = now_seconds();
            if (now - last_caption_press <= DOUBLE_CLICK_SECONDS &&
                fabsf(event->x - last_caption_x) <= DOUBLE_CLICK_DISTANCE &&
                fabsf(event->y - last_caption_y) <= DOUBLE_CLICK_DISTANCE) {
                last_caption_press = -10.0;
                pending = -1;
                dxc_native_window_action(1);
            } else {
                last_caption_press = now;
                last_caption_x = event->x;
                last_caption_y = event->y;
                pending = 0;
            }
        }
        break;
    }
    case DXC_EVENT_POINTER_MOVE:
        if (pending < 0) return;
        if (!(event->buttons & 1)) {
            pending = -1;
        } else if (fabsf(event->x - press_x) >= THRESHOLD || fabsf(event->y - press_y) >= THRESHOLD) {
            dxc_native_window_begin_drag_at(pending, press_x, press_y);
            pending = -1;
        }
        break;
    case DXC_EVENT_POINTER_UP:
        if (!(event->buttons & 1)) pending = -1;
        break;
    default:
        break;
    }
}

int main(int argc, char **argv) {
    const char *mode = argc > 1 ? argv[1] : "modern";
    int system_chrome = strcmp(mode, "system") == 0;
    const char *script = getenv("WM_CHROME_SCRIPT");
    if (script == NULL || script[0] == 0) {
        fprintf(stderr, "skip: WM_CHROME_SCRIPT is not set\n");
        return getenv("CI") != NULL ? 1 : 0;
    }
    const char *title = system_chrome ? "graalvm chrome system" : "graalvm chrome modern";
    dxc_native_window_configure(1, 0, 0, system_chrome, 0);
    struct dxc_native_window w;
    if (dxc_native_window_open(title, 400, 300, &w) != 0) {
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

    snprintf(command, sizeof command, "'%s' '%s' %s %d", script, title, mode, CAPTION);
    pthread_t driver;
    pthread_create(&driver, NULL, drive, NULL);
    double started = now_seconds();
    struct dxc_event event;
    while (!atomic_load(&driven) && now_seconds() - started < 180.0) {
        dxc_native_pump(0.016);
        while (dxc_native_poll_event(&event)) heard(&event);
    }
    for (int i = 0; i < 30; i++) {
        dxc_native_pump(0.016);
        while (dxc_native_poll_event(&event)) heard(&event);
    }
    pthread_join(driver, NULL);
    if (dxc_resize_serial > last_painted_serial) stretched += dxc_resize_serial - last_painted_serial;

    int64_t sizes = dxc_resize_serial - serial_before;
    printf("graalvm x11 chrome (%s): sizes=%lld frames=%d stale=%d stretched=%lld\n", mode,
           (long long)sizes, frames, stale, (long long)stretched);
    CHECK(drive_status == 0, "wm-chrome.sh found the chrome wrong; its output says what");
    if (!system_chrome) CHECK(sizes >= 8, "the edges took the window through fewer than eight sizes");
    CHECK(stale == 0, "a frame was drawn at a size the server had already left");
    CHECK(stretched == 0, "a size the server confirmed was left without a frame of its own");
    dxc_native_set_frame_callback(NULL, NULL);
    dxc_native_window_action(2);
    dxc_native_pump(0.0);
    return failures == 0 ? 0 : 1;
}
