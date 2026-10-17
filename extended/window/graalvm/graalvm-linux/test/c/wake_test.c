/*
 * The frame's wait in dxc_native_pump, ended early by dxc_native_wake from another thread,
 * played against x11_window.c itself. Also checks the focus and origin calls an AT-SPI
 * reader is answered from.
 *
 * Needs a display (CI runs it under Xvfb). Exit status 0 means every check passed.
 */
#include "../../c/x11_window.c"

#include <pthread.h>

static int failures;

#define CHECK(condition, message) \
    do { if (!(condition)) { fprintf(stderr, "FAIL: %s\n", message); failures++; } } while (0)

static double now_seconds(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (double)t.tv_sec + (double)t.tv_nsec / 1e9;
}

static void *wake_later(void *unused) {
    (void)unused;
    struct timespec pause = {0, 100 * 1000 * 1000};
    nanosleep(&pause, NULL);
    dxc_native_wake();
    return NULL;
}

/* Lets the window settle: mapping, exposure and focus events all read and answered. */
static void settle(void) {
    for (int i = 0; i < 30; i++) dxc_native_pump(0.01);
    struct dxc_event record;
    while (dxc_native_poll_event(&record)) {}
}

int main(void) {
    /* Before any window: a wake from anywhere is harmless. */
    dxc_native_wake();

    struct dxc_native_window w;
    if (dxc_native_window_open("wake", 320, 240, &w) != 0) {
        fprintf(stderr, "FAIL: no window\n");
        return 1;
    }
    settle();

    /* With nothing happening, the wait is the whole timeout. */
    double started = now_seconds();
    dxc_native_pump(0.3);
    double waited = now_seconds() - started;
    CHECK(waited >= 0.25, "an idle pump returned before its timeout");

    /* A wake from another thread ends a five second wait at once. */
    pthread_t waker;
    pthread_create(&waker, NULL, wake_later, NULL);
    started = now_seconds();
    dxc_native_pump(5.0);
    waited = now_seconds() - started;
    pthread_join(waker, NULL);
    fprintf(stderr, "woken after %.3f s\n", waited);
    CHECK(waited < 1.0, "a wake from another thread did not end the wait");
    CHECK(waited >= 0.05, "the pump returned before the wake was sent");

    /* A wake sent before the wait began ends it at once, and is answered only once. */
    dxc_native_wake();
    dxc_native_wake();
    started = now_seconds();
    dxc_native_pump(5.0);
    CHECK(now_seconds() - started < 1.0, "a wake sent before the wait did not end it");
    started = now_seconds();
    dxc_native_pump(0.3);
    CHECK(now_seconds() - started >= 0.25, "a wake was answered twice");

    /* What an AT-SPI reader asks of the window itself. */
    int32_t x = -1, y = -1;
    dxc_native_window_origin(&x, &y);
    CHECK(x >= 0 && y >= 0, "the window's origin on the screen is not a place on it");
    int32_t focused = dxc_native_window_focused();
    CHECK(focused == 0 || focused == 1, "focus is not a yes or a no");

    dxc_native_window_action(2);
    dxc_native_pump(0.0);
    if (failures == 0) fprintf(stderr, "wake_test: all checks passed\n");
    return failures == 0 ? 0 : 1;
}
