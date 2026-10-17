// When the frame drawn now reaches the screen, worked out from what the display link last said.
//
// The display link tells a thread of its own, once a refresh, when the frame after next will
// be shown and how long a refresh is. A frame is drawn whenever the window's loop gets to it,
// which is not on that thread and not at that moment, so the time it is given is the first
// presentation the link has announced that has not yet passed: the frame will be shown then
// and not before. Kept here with no AppKit and no CoreVideo in it, so a test can give it the
// times a display produces and read the answer.
//
// Everything is in host time ticks (mach_absolute_time), the clock the display link reports
// on and the one Rust's Instant reads on macOS.
#ifndef DXC_APPKIT_FRAME_TIME_H
#define DXC_APPKIT_FRAME_TIME_H

#include <stdint.h>

/**
 * The presentation time for a frame drawn at [now].
 *
 * [target] is the last presentation time the link announced and [period] the length of a
 * refresh, both zero while the link has said nothing. The answer is [now] when the link has
 * said nothing, when what it said is more than two refreshes old (it was stopped, or the
 * display slept), and when it points further ahead than two refreshes, which no display
 * does. Otherwise it is the first presentation at or after [now] on the link's schedule.
 */
static inline uint64_t dxc_frame_target(uint64_t now, uint64_t target, uint64_t period) {
    if (target == 0 || period == 0) {
        return now;
    }
    if (target >= now) {
        return target - now > 2 * period ? now : target;
    }
    uint64_t behind = now - target;
    if (behind > 2 * period) {
        return now;
    }
    // Rounded up to the next refresh: one that has already gone by cannot show this frame.
    uint64_t refreshes = (behind + period - 1) / period;
    return target + refreshes * period;
}

/** [ticks] of host time as nanoseconds, for a timebase of [numer] over [denom]. */
static inline int64_t dxc_ticks_to_nanos(uint64_t ticks, uint32_t numer, uint32_t denom) {
    // Split so that neither product overflows: a day of ticks times 125 is well inside 64 bits.
    return (int64_t)((ticks / denom) * numer + (ticks % denom) * numer / denom);
}

/** [seconds] as host time ticks, for a timebase of [numer] over [denom]. */
static inline uint64_t dxc_seconds_to_ticks(double seconds, uint32_t numer, uint32_t denom) {
    return (uint64_t)(seconds * 1.0e9 * denom / numer);
}

#endif
