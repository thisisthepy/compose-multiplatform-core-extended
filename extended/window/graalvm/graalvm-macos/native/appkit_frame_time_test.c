// The presentation time a frame is given, fed the times a 120 Hz display link reports.
//
// Plain C with no AppKit, so it runs anywhere: cc appkit_frame_time_test.c && ./a.out
#include "appkit_frame_time.h"

#include <stdio.h>
#include <stdlib.h>

static int failures;

static void expect(const char *what, uint64_t got, uint64_t want) {
    if (got != want) {
        fprintf(stderr, "FAIL %s: got %llu, want %llu\n", what, (unsigned long long)got,
                (unsigned long long)want);
        failures++;
    }
}

int main(void) {
    // Apple silicon's timebase: 125 nanoseconds per 3 ticks.
    const uint32_t numer = 125, denom = 3;
    const uint64_t period = dxc_seconds_to_ticks(1.0 / 120, numer, denom);
    const uint64_t start = 1000000000000ULL;

    expect("no link yet", dxc_frame_target(start, 0, 0), start);
    expect("a link that knows no period", dxc_frame_target(start, start + 10, 0), start);
    expect("a presentation still ahead", dxc_frame_target(start, start + period, period),
           start + period);
    expect("drawn exactly on a presentation", dxc_frame_target(start, start, period), start);
    expect("a presentation that just went by rounds to the next",
           dxc_frame_target(start + 1, start, period), start + period);
    expect("two refreshes late still lands on the schedule",
           dxc_frame_target(start + 2 * period, start, period), start + 2 * period);
    expect("a stopped link answers the time now",
           dxc_frame_target(start + 3 * period, start, period), start + 3 * period);
    expect("a target too far ahead answers the time now",
           dxc_frame_target(start, start + 3 * period, period), start);

    // A second of a 120 Hz display: frames drawn a third of the way into a refresh, some
    // refreshes missed, each told the link's latest announcement. Every frame is given a
    // presentation on the display's schedule, not before it was drawn and at most two
    // refreshes after, and the times never go backwards.
    const int skip[] = {1, 1, 3, 1, 2, 5, 1, 1, 2};
    uint64_t previous = 0;
    int i = 0;
    for (int refresh = 0; refresh <= 120; refresh += skip[i++ % (int)(sizeof skip / sizeof skip[0])]) {
        uint64_t now = start + (uint64_t)refresh * period + period / 3;
        uint64_t announced = start + (uint64_t)(refresh + 2) * period;
        uint64_t at = dxc_frame_target(now, announced, period);
        if ((at - start) % period != 0 || at < now || at - now > 2 * period || at < previous) {
            fprintf(stderr, "FAIL refresh %d: drawn at %llu, given %llu\n", refresh,
                    (unsigned long long)now, (unsigned long long)at);
            failures++;
        }
        previous = at;
    }

    expect("ticks to nanoseconds", (uint64_t)dxc_ticks_to_nanos(24000000, numer, denom),
           1000000000ULL);
    expect("ticks to nanoseconds on an Intel timebase",
           (uint64_t)dxc_ticks_to_nanos(1000000000ULL, 1, 1), 1000000000ULL);

    if (failures) {
        return EXIT_FAILURE;
    }
    printf("appkit frame time: all checks passed\n");
    return EXIT_SUCCESS;
}
