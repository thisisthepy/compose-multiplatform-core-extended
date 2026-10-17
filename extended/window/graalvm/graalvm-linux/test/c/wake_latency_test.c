/*
 * How long work posted from another thread waits before the window's thread runs it, measured
 * against x11_window.c itself.
 *
 * The window's thread sits in dxc_native_pump with a long timeout, which is the loop with
 * nothing happening. Another thread waits until it is in the wait, pauses so it is surely
 * blocked there, notes the monotonic clock, and wakes it. The window's thread notes the clock
 * when the pump returns, which is where the loop runs what was posted. Before the wake, that
 * was up to a frame's wait (16 ms) later.
 *
 * Prints the median, the 99th percentile and the worst of 200 posts, and fails when the 99th
 * percentile is 1 ms or more. The worst is reported rather than asserted: a shared CI machine
 * can deschedule either thread for longer than that, which is a fact about the machine and not
 * about the wake. Needs a display (CI runs it under Xvfb).
 */
#include "../../c/x11_window.c"

#include <pthread.h>
#include <stdatomic.h>

#define POSTS 200

static atomic_int waiting;      /* the window's thread is about to wait */
static atomic_llong posted_at;  /* when the other thread woke it, in ns */
static atomic_int finished;

static long long now_nanos(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (long long)t.tv_sec * 1000000000LL + t.tv_nsec;
}

static void *poster(void *unused) {
    (void)unused;
    for (int i = 0; i < POSTS; i++) {
        while (!atomic_load(&waiting)) {
            if (atomic_load(&finished)) return NULL;
            struct timespec spin = {0, 50000};
            nanosleep(&spin, NULL);
        }
        /* Long enough that the window's thread is blocked in its wait, not on its way in. */
        struct timespec pause = {0, 3 * 1000 * 1000};
        nanosleep(&pause, NULL);
        atomic_store(&waiting, 0);
        atomic_store(&posted_at, now_nanos());
        dxc_native_wake();
    }
    return NULL;
}

static int compare(const void *a, const void *b) {
    long long x = *(const long long *)a, y = *(const long long *)b;
    return (x > y) - (x < y);
}

int main(void) {
    struct dxc_native_window w;
    if (dxc_native_window_open("wake latency", 320, 240, &w) != 0) {
        fprintf(stderr, "FAIL: no window\n");
        return 1;
    }
    for (int i = 0; i < 30; i++) dxc_native_pump(0.01);

    long long samples[POSTS];
    pthread_t thread;
    pthread_create(&thread, NULL, poster, NULL);
    int taken = 0;
    while (taken < POSTS) {
        atomic_store(&posted_at, 0);
        atomic_store(&waiting, 1);
        /* The loop with nothing happening: a wait far longer than any wake should take. */
        dxc_native_pump(5.0);
        long long ran = now_nanos();
        long long posted = atomic_load(&posted_at);
        if (posted == 0) continue; /* the server said something first; wait again */
        samples[taken++] = ran - posted;
    }
    atomic_store(&finished, 1);
    pthread_join(thread, NULL);

    qsort(samples, POSTS, sizeof samples[0], compare);
    double median = samples[POSTS / 2] / 1e6;
    double p99 = samples[(POSTS * 99) / 100 - 1] / 1e6;
    double worst = samples[POSTS - 1] / 1e6;
    printf("graalvm x11 wake latency over %d posts: median=%.3f ms p99=%.3f ms max=%.3f ms\n",
           POSTS, median, p99, worst);
    dxc_native_window_action(2);
    dxc_native_pump(0.0);
    if (p99 >= 1.0) {
        fprintf(stderr, "FAIL: the 99th percentile is %.3f ms; work posted from another thread waits too long\n", p99);
        return 1;
    }
    if (worst >= 1.0) {
        printf("note: the worst post took %.3f ms, beyond 1 ms, on this machine\n", worst);
    }
    return 0;
}
